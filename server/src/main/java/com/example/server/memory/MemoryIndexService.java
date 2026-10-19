package com.example.server.memory;

import com.example.server.utils.EmbeddingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

/** Transactional SQL outbox -> eventually consistent index. SQL state/hash always gate retrieval. */
@Service
public class MemoryIndexService {
    private final JdbcTemplate jdbc;
    private final MemoryRepository repository;
    private final MemoryVectorStore vectors;
    private final EmbeddingUtils embeddings;
    private final long timeout;
    public MemoryIndexService(DataSource source,MemoryRepository repository,MemoryVectorStore vectors,EmbeddingUtils embeddings,
                              @Value("${memory.io-timeout-ms:1500}") long timeout) {
        jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(2); this.repository=repository; this.vectors=vectors; this.embeddings=embeddings;
        this.timeout=Math.clamp(timeout,100,5000);
    }
    public int drain(int limit) {
        int done=0;
        for(var row:jdbc.queryForList("SELECT user_id,memory_id,version,attempts FROM agent_memory_index_outbox WHERE available_at<=CURRENT_TIMESTAMP(3) ORDER BY available_at LIMIT ?",
                Math.clamp(limit,1,20))) {
            Long user=((Number)row.get("user_id")).longValue(); String id=(String)row.get("memory_id");
            long version=((Number)row.get("version")).longValue(); int attempts=((Number)row.get("attempts")).intValue();
            try {
                AgentMemory memory=repository.find(user,id);
                if(memory==null || memory.status()!=AgentMemory.Status.ACTIVE) vectors.delete(user,id);
                else vectors.upsert(memory,embeddings.embed(memory.content(),timeout));
                done+=jdbc.update("DELETE FROM agent_memory_index_outbox WHERE user_id=? AND memory_id=? AND version=?",user,id,version);
            } catch(RuntimeException e) {
                jdbc.update("UPDATE agent_memory_index_outbox SET attempts=attempts+1,available_at=?,last_error=? WHERE user_id=? AND memory_id=? AND version=?",
                        Timestamp.from(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant().plusSeconds(Math.min(300,1L<<Math.min(attempts+1,8)))),e.getClass().getSimpleName(),user,id,version);
            }
        }
        return done;
    }
}
