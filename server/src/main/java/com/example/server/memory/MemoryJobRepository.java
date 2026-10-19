package com.example.server.memory;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Durable optional-work queue, independent of RocketMQ video task status. Leases fence completion across instances. */
@Repository
public class MemoryJobRepository {
    public record Work(MemoryRequest request,AgentState state,AgentFeedback feedback) { }
    public record Job(String id,Long user,String payload,String token,int attempts) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public MemoryJobRepository(DataSource source,ObjectMapper json) { jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(2); this.json=json; }
    public void enqueue(Work work) {
        try {
            String payload=json.writeValueAsString(work);
            String kind=work.feedback()==null ? "TASK" : "FEEDBACK";
            String checkpoint=work.request().checkpointKey();
            String id=MemoryRepository.hash(kind+":"+work.request().userId()+":"+work.request().videoId()+":"
                    +(checkpoint==null ? work.request().taskId() : checkpoint)+":"
                    +(work.feedback()==null ? json.writeValueAsString(work.state()) : payload));
            if(payload.length()>40000 || !MemorySafety.safe(payload)) {
                if(checkpoint!=null) skipped(id,work.request().userId(),work.request().videoId(),checkpoint,"UnsafeOrOversizeWork");
                return;
            }
            jdbc.update("""
                    INSERT INTO agent_memory_jobs(job_id,user_id,kind,payload,source_media_id,source_checkpoint_key) VALUES(?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE source_seen_at=CURRENT_TIMESTAMP(3)
                    """,id,work.request().userId(),kind,payload,work.request().videoId(),checkpoint);
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Invalid memory work"); }
    }
    /** Repairs the checkpoint->enqueue crash gap. Recover only verified episodic/result data, never infer user preferences. */
    public int recoverCompleted() {
        int recovered=0;
        var rows=jdbc.queryForList("""
                SELECT c.media_id,c.checkpoint_key,c.payload,m.user_id FROM agent_checkpoints c
                JOIN media_files m ON m.id=c.media_id
                WHERE c.stage='ANALYSIS_COMPLETED' AND c.checkpoint_key LIKE 'goal:%:result' AND c.updated_at<?
                AND NOT EXISTS (SELECT 1 FROM agent_memory_jobs j WHERE j.user_id=m.user_id AND j.source_media_id=c.media_id
                    AND j.source_checkpoint_key=c.checkpoint_key AND j.source_seen_at>=c.updated_at)
                ORDER BY c.updated_at LIMIT 10
                """,Timestamp.from(databaseNow().minusSeconds(10)));
        for(var row:rows) {
            try {
                AgentState state=json.readValue((String)row.get("payload"),AgentState.class);
                if(state.critique()==null || !state.critique().passed()) {
                    skippedSource(row,"UnverifiedCheckpoint"); continue;
                }
                String key=(String)row.get("checkpoint_key");
                long video=((Number)row.get("media_id")).longValue(),user=((Number)row.get("user_id")).longValue();
                var request=new MemoryRequest(user,video,video+":"+key.substring(5,key.length()-7),null,
                        new com.example.server.dto.GoalContext(null,null,state.goal()),key,false);
                enqueue(new Work(request,state,null)); recovered++;
            } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
                skippedSource(row,"InvalidCheckpoint");
            }
        }
        return recovered;
    }
    private void skippedSource(Map<String,Object> row,String reason) {
        Long user=((Number)row.get("user_id")).longValue(),video=((Number)row.get("media_id")).longValue();
        String key=(String)row.get("checkpoint_key");
        skipped(MemoryRepository.hash("skipped:"+user+":"+video+":"+key),user,video,key,reason);
    }
    private void skipped(String id,Long user,Long video,String key,String reason) {
        jdbc.update("""
                INSERT INTO agent_memory_jobs(job_id,user_id,kind,status,last_error,source_media_id,source_checkpoint_key)
                VALUES(?,?,'TASK','SKIPPED',?,?,?) ON DUPLICATE KEY UPDATE source_seen_at=CURRENT_TIMESTAMP(3)
                """,id,user,reason,video,key);
    }
    @Transactional(timeout=4)
    public Job claim() {
        Instant now=databaseNow();
        var rows=jdbc.queryForList("""
                SELECT job_id,user_id,payload,attempts FROM agent_memory_jobs
                WHERE (status='PENDING' AND available_at<=?) OR (status='RUNNING' AND lease_until<?)
                ORDER BY created_at,job_id LIMIT 1 FOR UPDATE
                """,Timestamp.from(now),Timestamp.from(now));
        if(rows.isEmpty()) return null;
        var r=rows.getFirst(); String id=(String)r.get("job_id"),token=UUID.randomUUID().toString();
        int attempts=((Number)r.get("attempts")).intValue()+1;
        if(attempts>5) {
            jdbc.update("UPDATE agent_memory_jobs SET status='DEAD',lease_token=NULL,last_error='LeaseAttemptsExceeded' WHERE job_id=?",id);
            return null;
        }
        jdbc.update("UPDATE agent_memory_jobs SET status='RUNNING',attempts=?,lease_token=?,lease_until=? WHERE job_id=?",
                attempts,token,Timestamp.from(now.plusSeconds(300)),id);
        return new Job(id,((Number)r.get("user_id")).longValue(),(String)r.get("payload"),token,attempts);
    }
    public Work decode(Job job) {
        try {
            Work w=json.readValue(job.payload(),Work.class);
            if(!Objects.equals(w.request().userId(),job.user())) throw new IllegalArgumentException("Work owner mismatch");
            return w;
        } catch(Exception e) { throw new IllegalArgumentException("Invalid durable memory work"); }
    }
    public boolean owns(Job job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM agent_memory_jobs WHERE job_id=? AND lease_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(3)",
                Boolean.class,job.id(),job.token()));
    }
    public void done(Job job) {
        jdbc.update("UPDATE agent_memory_jobs SET status='DONE',payload=NULL,lease_token=NULL,lease_until=NULL,last_error=NULL WHERE job_id=? AND lease_token=?",
                job.id(),job.token());
    }
    public void failed(Job job,Exception error) {
        jdbc.update("UPDATE agent_memory_jobs SET status=?,available_at=?,lease_token=NULL,lease_until=NULL,last_error=? WHERE job_id=? AND lease_token=?",
                job.attempts()>=5 ? "DEAD" : "PENDING",Timestamp.from(databaseNow().plusSeconds(Math.min(300,1L<<job.attempts()))),
                error.getClass().getSimpleName(),job.id(),job.token());
    }
    public int retryDead(Long user) {
        return jdbc.update("UPDATE agent_memory_jobs SET status='PENDING',attempts=0,available_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND status='DEAD' AND payload IS NOT NULL",user);
    }
    private Instant databaseNow() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant(); }
}
