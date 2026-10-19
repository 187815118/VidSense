package com.example.server.memory;

import com.example.server.dto.*;
import com.example.server.service.AgentTelemetry;
import com.example.server.utils.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static com.example.server.memory.AgentMemory.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Explicit opt-in: REAL local LLM/embedding/MySQL/Qdrant; structured task fixtures, NOT a video E2E. */
class MemoryLiveIT {
    @Test void actualLocalModelSqlIndexSemanticMergeCompressionAndRecall() throws Exception {
        assertTrue(System.getenv().getOrDefault("MEMORY_TEST_MYSQL_URL","").contains("/dovideo_memory_test"));
        String base=System.getenv().getOrDefault("MEMORY_LIVE_BASE_URL","http://127.0.0.1:11436/v1");
        assertEquals("http://127.0.0.1:11436/v1",base,"Local model only");
        String collection="memory_upgrade_live_"+UUID.randomUUID().toString().replace("-","");
        String qdrant="http://127.0.0.1:16333";
        var f=new MemoryFlowTest(); f.setup();
        var executor=new ThreadPoolTaskExecutor(); executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(4); executor.initialize();
        try {
            var json=new ObjectMapper().findAndRegisterModules();
            var llm=new DeepSeekUtils("local-only",base,"dovideo-qwen2.5-7b-gpu0",60,0,0,0,mock(AgentTelemetry.class),json,executor);
            var extractor=new MemoryLlmExtractor(llm,json,true,30000);
            var semantic=new MemorySemanticResolver(llm,json,true,30000);
            var embeddings=new EmbeddingUtils("local-only",base,"dovideo-qwen3-embedding-gpu1");
            var vectors=new MemoryVectorStore(qdrant,"",collection,"video_chunks",5000);
            var service=new MemoryService(true,10,5000,new MemoryGate(),f.repository,vectors,embeddings,new MemoryExtractor());
            var jobs=new MemoryJobRepository(f.context.getBean(DataSource.class),json);
            var compression=new MemoryConsolidationService(f.repository,true,4,4,0,200);
            service.configureBackground(new MemoryExtractionService(new MemoryExtractor(),extractor,false),semantic,jobs,null);
            var first=MemoryFlowTest.request(1,10,"我喜欢详细回答。请总结课程","a");
            service.processCompleted(first,MemoryFlowTest.success(first.goals().currentGoal()));
            AgentMemory old=f.repository.active(1L,10L,"a").stream().filter(m->m.type()==Type.USER_PREFERENCE).findFirst().orElseThrow();
            assertEquals("USER_REQUEST",old.metadata().source());
            var second=MemoryFlowTest.request(1,11,"以后请简洁回答。请总结课程","b");
            service.processCompleted(second,MemoryFlowTest.success(second.goals().currentGoal()));
            assertEquals(Status.SUPERSEDED,f.repository.find(1L,old.memoryId()).status());
            assertTrue(f.repository.active(1L,11L,"b").stream().anyMatch(m->m.type()==Type.USER_PREFERENCE && m.content().contains("简洁")));
            AgentMemory brief=f.repository.active(1L,11L,"b").stream().filter(m->m.type()==Type.USER_PREFERENCE).findFirst().orElseThrow();
            var synonymTarget=f.repository.merge(second,new Candidate(Type.USER_PREFERENCE,"我喜欢中文回答",.85,Scope.GLOBAL_USER,"custom.chinese",
                    new MemoryMetadata("USER_REQUEST",.99,"我喜欢中文回答",false,"","",0,List.of(),List.of(second.taskId()))));
            Candidate synonym=new Candidate(Type.USER_PREFERENCE,"我偏好用中文回答",.85,Scope.GLOBAL_USER,"custom.language",
                    new MemoryMetadata("USER_REQUEST",.99,"我偏好用中文回答",false,"","",0,List.of(),List.of(second.taskId())));
            var relation=semantic.resolve(second,synonym,List.of(synonymTarget));
            assertEquals(MemorySemanticResolver.Relation.DUPLICATE,relation.relation(),"Real model semantic equivalence");
            assertEquals(synonymTarget.memoryId(),f.repository.mergeResolved(second,synonym,relation).memoryId());
            for(int i=0;i<4;i++) {
                var r=MemoryFlowTest.request(1,20+i,"学习任务"+i,"b");
                f.repository.merge(r,new Candidate(Type.EPISODIC_TASK,"课程历史任务"+i+"：提取知识点",.8,Scope.GLOBAL_USER,"learning:"+i,
                        new MemoryMetadata("VERIFIED_TASK",1,"",false,"","same-course",0,List.of(),List.of(r.taskId()))));
            }
            AgentMemory summary=compression.compact(1L); assertNotNull(summary);
            var index=new MemoryIndexService(f.context.getBean(DataSource.class),f.repository,vectors,embeddings,5000);
            assertTrue(index.drain(20)>0);
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_index_outbox",Integer.class));
            var request=MemoryFlowTest.request(1,99,"按照上次的方式继续，按我的偏好总结课程","new-session");
            var hits=vectors.search(request,embeddings.embed(request.goals().currentGoal(),5000),20);
            assertTrue(hits.stream().anyMatch(h->h.memoryId().equals(summary.memoryId())));
            var recalled=service.prepare(request);
            assertNotNull(recalled); assertTrue(recalled.memories().stream().anyMatch(m->m.memoryId().equals(summary.memoryId())));
            assertTrue(recalled.memories().stream().anyMatch(m->m.type()==Type.USER_PREFERENCE));
            assertEquals(4,f.repository.sourceLinks(1L,summary.memoryId()).size());
            assertNull(service.prepare(MemoryFlowTest.request(2,99,"按照上次继续","other-session")));
            System.out.println("REAL_MEMORY_INFRA_PASS: local Qwen7B + actual embedding + MySQL + Qdrant; video Agent uses structured fixtures, not E2E");
        } finally {
            executor.shutdown(); f.close();
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(qdrant+"/collections/"+collection)).DELETE().build(),HttpResponse.BodyHandlers.discarding());
        }
    }
}
