package com.example.server.memory;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.example.server.memory.AgentMemory.*;

@Service
public class MemoryExtractionService {
    private final MemoryExtractor rules;
    private final MemoryLlmExtractor llm;
    private final boolean fallback;
    public MemoryExtractionService(MemoryExtractor rules, MemoryLlmExtractor llm,
                                   @Value("${memory.rule-extraction.enabled:true}") boolean fallback) {
        this.rules=rules; this.llm=llm; this.fallback=fallback;
    }
    public List<Candidate> completed(MemoryRequest request, AgentState state) {
        List<Candidate> deterministic=rules.extract(request,state);
        List<Candidate> out=new ArrayList<>(deterministic.stream()
                .filter(c -> c.type()==Type.EPISODIC_TASK || c.type()==Type.PREVIOUS_RESULT).toList());
        if(!request.trustedUserText()) {
            // A follow-up checkpoint goal may contain an old report. Recover actual plan/result data only.
            return out.stream().map(c -> {
                MemoryMetadata m=c.metadata();
                String content=c.type()==Type.EPISODIC_TASK ? "已完成视频分析任务；执行内容："
                        +String.join("；",state.plan().tasks().stream().limit(3).toList())+"；产物："+state.result().title() : c.content();
                if(content.length()>1200) content=content.substring(0,1200);
                return new Candidate(c.type(),content,c.importance(),c.scope(),c.semanticKey(),
                        new MemoryMetadata("VERIFIED_TASK_RECOVERY",m.confidence(),"",false,"",m.topicKey(),m.level(),m.sourceMemoryIds(),m.sourceTaskIds()));
            }).toList();
        }
        out.addAll(trusted(request,request.goals().currentGoal(),false,
                deterministic.stream().filter(c -> c.type()!=Type.EPISODIC_TASK && c.type()!=Type.PREVIOUS_RESULT).toList()));
        return out;
    }
    public List<Candidate> feedback(MemoryRequest request, AgentFeedback feedback) {
        List<Candidate> deterministic=rules.feedback(feedback);
        List<Candidate> out=new ArrayList<>(deterministic.stream().filter(c -> c.type()==Type.USER_FEEDBACK).toList());
        out.addAll(trusted(request,feedback.comment()==null ? "" : feedback.comment(),true,
                deterministic.stream().filter(c -> c.type()!=Type.USER_FEEDBACK).toList()));
        return out.stream().map(c -> {
            MemoryMetadata m=c.metadata();
            return new Candidate(c.type(),c.content(),c.importance(),c.scope(),c.semanticKey(),
                    new MemoryMetadata("USER_FEEDBACK",m.confidence(),m.quote(),m.correction(),m.condition(),m.topicKey(),
                            m.level(),m.sourceMemoryIds(),m.sourceTaskIds()));
        }).toList();
    }
    private List<Candidate> trusted(MemoryRequest req, String text, boolean feedback, List<Candidate> ruleCandidates) {
        if (!MemorySafety.safe(text)) return List.of();
        if (llm != null && llm.enabled()) {
            try { return llm.extract(req,text,feedback); }
            catch (RuntimeException e) { if (!fallback) throw e; }
        }
        return fallback ? ruleCandidates : List.of();
    }
}
