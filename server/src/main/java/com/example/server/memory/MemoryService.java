package com.example.server.memory;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.utils.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.example.server.memory.AgentMemory.*;

/** Optional augmentation boundary: all I/O and extraction failures are contained here. */
@Service
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private final boolean enabled;
    private final int topK;
    private final long timeoutMs;
    private final MemoryGate gate;
    private final MemoryRepository repository;
    private final MemoryVectorStore vectors;
    private final EmbeddingUtils embeddings;
    private final MemoryExtractor extractor;
    private MemoryExtractionService extraction;
    private MemorySemanticResolver semantic;
    private MemoryJobRepository jobs;
    private MemoryConsolidationService compression;

    public MemoryService(@Value("${memory.enabled:false}") boolean enabled,
                         @Value("${memory.top-k:5}") int topK,
                         @Value("${memory.io-timeout-ms:1500}") long timeoutMs,
                         MemoryGate gate, MemoryRepository repository, MemoryVectorStore vectors,
                         EmbeddingUtils embeddings, MemoryExtractor extractor) {
        this.enabled = enabled;
        this.topK = Math.clamp(topK, 1, 10);
        this.timeoutMs = Math.clamp(timeoutMs, 100, 5000);
        this.gate = gate;
        this.repository = repository;
        this.vectors = vectors;
        this.embeddings = embeddings;
        this.extractor = extractor;
        this.extraction = new MemoryExtractionService(extractor, null, true);
    }

    @Autowired
    public void configureBackground(MemoryExtractionService extraction, MemorySemanticResolver semantic,
                                    MemoryJobRepository jobs, MemoryConsolidationService compression) {
        this.extraction=extraction; this.semantic=semantic; this.jobs=jobs; this.compression=compression;
    }

    public boolean enabled() { return enabled; }

    /** No model/SQL/index I/O: also used by application/controller cache fast paths. */
    public boolean requiresHistory(String query) {
        if (!enabled) return false;
        try { return gate.evaluate(query).retrieve(); }
        catch (RuntimeException e) { failed("gate",e); return false; }
    }

    public MemoryContext prepare(MemoryRequest request) {
        if (!available(request)) return null;
        try {
            MemoryGate.Decision decision = gate.evaluate(request.goals().currentGoal());
            if (!decision.retrieve()) return null;
            List<MemoryVectorStore.Hit> hits = List.of();
            try {
                hits = vectors.search(request, embeddings.embed(request.goals().currentGoal(), timeoutMs), topK * 8);
            } catch (RuntimeException error) {
                failed("retrieval_index", error);
                // Explicit history requests can still use recent episodes/preferences from authoritative SQL.
            }
            Map<String, MemoryVectorStore.Hit> byId = new HashMap<>();
            for (MemoryVectorStore.Hit hit : hits) byId.put(hit.memoryId(), hit);
            Map<String, Double> similarities = new HashMap<>();
            List<AgentMemory> candidates = repository.active(request.userId(), request.videoId(), request.sessionId()).stream()
                    .filter(memory -> visible(request, memory))
                    .filter(memory -> {
                        MemoryVectorStore.Hit hit = byId.get(memory.memoryId());
                        double semantic = hit != null && Objects.equals(hit.contentHash(), memory.contentHash())
                                && Double.isFinite(hit.similarity()) ? Math.clamp(hit.similarity(), 0, 1) : 0;
                        if (decision.episodic() && memory.type() == Type.EPISODIC_TASK) semantic = Math.max(semantic, .65);
                        if (memory.type() == Type.USER_PREFERENCE) semantic = Math.max(semantic, .45);
                        similarities.put(memory.memoryId(), semantic);
                        return semantic >= .25;
                    }).toList();
            Instant now = Instant.now();
            List<AgentMemory> relevant = candidates.stream().sorted(Comparator
                            .<AgentMemory>comparingDouble(m -> score(m, similarities.get(m.memoryId()), now)).reversed()
                            .thenComparing(AgentMemory::updatedAt, Comparator.reverseOrder())
                            .thenComparing(AgentMemory::memoryId))
                    .limit(topK).toList();
            return relevant.isEmpty() ? null : new MemoryContext(request.goals(), relevant);
        } catch (RuntimeException error) {
            failed("gate_or_store_read", error);
            return null;
        }
    }

    public void completed(MemoryRequest request, AgentState state) {
        if (!available(request) || state == null || state.result() == null
                || state.critique() == null || !state.critique().passed()) return;
        try {
            if(jobs!=null) jobs.enqueue(new MemoryJobRepository.Work(request,state,null));
        } catch (RuntimeException error) {
            failed("enqueue_task", error);
        }
    }

    public void feedback(MemoryRequest request, AgentFeedback feedback) {
        if (!available(request)) return;
        try {
            if(jobs!=null) jobs.enqueue(new MemoryJobRepository.Work(request,null,feedback));
        } catch (RuntimeException error) {
            failed("enqueue_feedback", error);
        }
    }

    /** Worker entry; exceptions intentionally propagate to durable retry, never to the video task. */
    public void processCompleted(MemoryRequest request, AgentState state) {
        if (!available(request) || state==null || state.critique()==null || !state.critique().passed()) return;
        store(request,extraction.completed(request,state));
    }
    public void processFeedback(MemoryRequest request, AgentFeedback feedback) {
        if (available(request)) store(request,extraction.feedback(request,feedback));
    }
    public List<String> requestReindex(Long user,String after,int limit) {
        return enabled ? repository.requestReindex(user,after,limit) : List.of();
    }
    public List<Map<String,Object>> sources(Long user,String id) {
        return enabled ? repository.sourceLinks(user,id) : List.of();
    }
    public int retryFailed(Long user) { return enabled && jobs!=null ? jobs.retryDead(user) : 0; }

    public boolean disable(Long userId, String memoryId) {
        if (!enabled || userId == null) return false;
        try {
            boolean changed = repository.disable(userId, memoryId);
            return changed;
        } catch (RuntimeException error) {
            failed("disable", error);
            return false;
        }
    }

    private void store(MemoryRequest request, List<Candidate> candidates) {
        compactSafely(request.userId());
        for (Candidate candidate : candidates.stream().limit(6).toList()) {
            MemorySafety.validate(request,candidate);
            if(semantic==null) repository.merge(request,candidate);
            else repository.mergeResolved(request,candidate,semantic.resolve(request,candidate,
                    related(request,candidate)));
        }
        compactSafely(request.userId());
    }

    private void compactSafely(Long user) {
        if(compression==null) return;
        try { compression.compact(user); }
        catch(RuntimeException e) { failed("compression_retry",e); }
    }

    private List<AgentMemory> related(MemoryRequest request,Candidate c) {
        if(!semantic.enabled() || c.type()==Type.EPISODIC_TASK || c.type()==Type.PREVIOUS_RESULT) return List.of();
        List<AgentMemory> rows=repository.active(request.userId(),request.videoId(),request.sessionId()).stream()
                .filter(m -> MemorySemanticResolver.compatible(request,c,m)).toList();
        if(rows.isEmpty()) return rows;
        Map<String,MemoryVectorStore.Hit> hits=new HashMap<>();
        try {
            for(var hit:vectors.search(request,embeddings.embed(c.content(),timeoutMs),40)) hits.put(hit.memoryId(),hit);
        } catch(RuntimeException e) {failed("semantic_candidates_index",e);}
        return rows.stream().sorted(Comparator.<AgentMemory>comparingDouble(m -> {
            var hit=hits.get(m.memoryId());
            double similarity=hit!=null && m.contentHash().equals(hit.contentHash()) && Double.isFinite(hit.similarity()) ? hit.similarity() : 0;
            return (c.semanticKey().equals(m.semanticKey()) ? 2 : 0)+similarity;
        }).reversed().thenComparing(AgentMemory::updatedAt,Comparator.reverseOrder())).limit(6).toList();
    }

    private boolean available(MemoryRequest request) {
        return enabled && request != null && request.userId() != null && request.goals() != null
                && request.taskId() != null && !request.goals().currentGoal().isBlank();
    }

    private boolean visible(MemoryRequest request, AgentMemory memory) {
        return Objects.equals(request.userId(), memory.userId()) && memory.status() == Status.ACTIVE && MemorySafety.safe(memory.content())
                && switch (memory.scope()) {
                    case GLOBAL_USER -> true;
                    case VIDEO -> Objects.equals(request.videoId(), memory.sourceVideoId()) && request.videoId() != null;
                    case SESSION -> Objects.equals(request.sessionId(), memory.sessionId()) && request.sessionId() != null;
                };
    }

    static double score(AgentMemory memory, double similarity, Instant now) {
        double ageDays = Math.max(0, Duration.between(memory.updatedAt(), now).toSeconds()) / 86400.0;
        return .55 * similarity + .25 * Math.clamp(memory.importance(), 0, 1) + .20 * Math.exp(-ageDays / 30);
    }

    private void failed(String stage, RuntimeException error) {
        // Do not log query, remembered content, credentials, or raw remote response bodies.
        log.warn("agent_memory_fail_open stage={} error={}", stage, error.getClass().getSimpleName());
    }
}
