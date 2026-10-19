package com.example.server.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.example.server.memory.AgentMemory.*;

/** Conservative hierarchical, extractive compression. Source wording is retained losslessly, not invented by a model. */
@Service
public class MemoryConsolidationService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MemoryConsolidationService.class);
    private final MemoryRepository repository;
    private final boolean enabled;
    private final int trigger,batch,ageDays;
    public MemoryConsolidationService(MemoryRepository repository,
            @Value("${memory.consolidation.enabled:false}") boolean enabled,
            @Value("${memory.consolidation.trigger-count:150}") int trigger,
            @Value("${memory.consolidation.batch-size:6}") int batch,
            @Value("${memory.consolidation.min-age-days:7}") int ageDays,
            @Value("${memory.max-per-user:200}") int maxActive) {
        if(enabled && (trigger<2 || trigger>=maxActive || batch<2 || batch>16 || ageDays<0)) throw new IllegalArgumentException("Invalid compression limits");
        this.repository=repository; this.enabled=enabled; this.trigger=trigger; this.batch=batch; this.ageDays=ageDays;
    }
    public void sweep() {
        if(enabled) for(Long user:repository.compressionUsers(trigger)) {
            try { compact(user); }
            catch(RuntimeException e) { log.warn("memory_compression_retry userId={} error={}",user,e.getClass().getSimpleName()); }
        }
    }
    public AgentMemory compact(Long user) {
        if(!enabled || repository.activeCount(user)<trigger) return null;
        Map<String,List<AgentMemory>> groups=new LinkedHashMap<>();
        for(AgentMemory m:repository.compressionCandidates(user,ageDays,200)) {
            if(m.metadata().topicKey().isBlank() || m.metadata().level()>=8 || !m.metadata().condition().isBlank()) continue;
            String scope=m.scope()+":"+(m.scope()==Scope.VIDEO ? m.sourceVideoId() : m.scope()==Scope.SESSION ? m.sessionId() : "global");
            String key=m.type()+":"+scope+":"+m.metadata().topicKey();
            List<AgentMemory> g=groups.computeIfAbsent(key,k->new ArrayList<>());
            if(g.size()<batch) g.add(m);
        }
        for(List<AgentMemory> group:groups.values().stream()
                .sorted(Comparator.<List<AgentMemory>>comparingInt(List::size).reversed()).toList()) {
            // Reduce the batch instead of truncating information to fit VARCHAR(1200).
            while(group.size()>=2 && losslessContent(group).length()>1200) group.removeLast();
            if(group.size()<2) continue;
            AgentMemory first=group.getFirst();
            List<String> ids=group.stream().map(AgentMemory::memoryId).toList();
            // Direct edges keep metadata bounded; recursive source links preserve every stored descendant.
            List<String> tasks=group.stream().map(AgentMemory::sourceTaskId).distinct().toList();
            int level=group.stream().mapToInt(m -> m.metadata().level()).max().orElse(0)+1;
            Candidate summary=new Candidate(first.type(),losslessContent(group),group.stream().mapToDouble(AgentMemory::importance).max().orElse(.65),
                    first.scope(),"summary:"+MemoryRepository.hash(String.join(",",ids.stream().sorted().toList())),
                    new MemoryMetadata("LOSSLESS_COMPRESSION",1,"",false,"",first.metadata().topicKey(),level,ids,tasks));
            AgentMemory saved=repository.compress(user,List.copyOf(group),summary);
            if(saved!=null) return saved;
        }
        return null;
    }
    public static String losslessContent(List<AgentMemory> rows) {
        // Factor an identical verified execution plan once; reconstruct every source string before accepting it.
        if(rows.stream().allMatch(m -> m.type()==Type.EPISODIC_TASK && m.metadata().level()==0)) {
            List<String> goals=new ArrayList<>(),plans=new ArrayList<>(),titles=new ArrayList<>();
            for(AgentMemory m:rows) {
                String s=m.content(); int a=s.lastIndexOf("；执行内容："),b=s.lastIndexOf("；产物：");
                if(!s.startsWith("已完成任务：") || a<6 || b<a+6) { plans.clear(); break; }
                String goal=s.substring(6,a),plan=s.substring(a+6,b),title=s.substring(b+4);
                if(!("已完成任务："+goal+"；执行内容："+plan+"；产物："+title).equals(s)) {plans.clear();break;}
                goals.add(goal); plans.add(plan); titles.add(title);
            }
            if(plans.size()==rows.size() && plans.stream().distinct().count()==1) {
                List<String> entries=new ArrayList<>();
                for(int i=0;i<rows.size();i++) entries.add((i+1)+". "+goals.get(i)+"；产物："+titles.get(i));
                String factored="历史任务整合：\n共同执行内容："+plans.getFirst()+"\n各次任务与产物：\n"+String.join("\n",entries);
                if(factored.length()<rows.stream().mapToInt(m->m.content().length()).sum()) return factored;
            }
        }
        // Hierarchy is recorded in metadata + source links; no source wording is dropped to fit the limit.
        return "历史任务整合（来源可追溯）：\n"+String.join("\n",rows.stream().map(AgentMemory::content).toList());
    }
}
