package com.example.server.memory;

import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static com.example.server.memory.AgentMemory.*;
import static com.example.server.memory.MemorySemanticResolver.*;

/** MySQL authority. Owner row lock serializes mutation across instances; model I/O stays OUTSIDE transactions. */
@Repository
public class MemoryRepository {
    private final JdbcTemplate jdbc;
    private final int maxActive;
    public MemoryRepository(DataSource source, @Value("${memory.max-per-user:200}") int maxActive) {
        jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(2); this.maxActive=Math.clamp(maxActive,5,1000);
    }
    public List<AgentMemory> active(Long user, Long video, String session) {
        return jdbc.query("""
                SELECT * FROM agent_memories WHERE user_id=? AND status='ACTIVE'
                AND (scope='GLOBAL_USER' OR (scope='VIDEO' AND source_video_id=?) OR (scope='SESSION' AND session_id=?))
                ORDER BY updated_at DESC,memory_id LIMIT ?
                """,this::map,user,video,session,maxActive);
    }
    public AgentMemory find(Long user,String id) {
        return jdbc.query("SELECT * FROM agent_memories WHERE user_id=? AND memory_id=?",this::map,user,id).stream().findFirst().orElse(null);
    }
    public int activeCount(Long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_memories WHERE user_id=? AND status='ACTIVE'",Integer.class,user);
    }
    private void lock(Long user) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user).isEmpty())
            throw new IllegalArgumentException("Memory owner does not exist");
    }
    private String scopeKey(MemoryRequest r, Scope scope) {
        return switch(scope) { case GLOBAL_USER -> "GLOBAL_USER"; case VIDEO -> "VIDEO:"+Objects.requireNonNull(r.videoId());
            case SESSION -> "SESSION:"+Objects.requireNonNull(r.sessionId()); };
    }
    @Transactional(timeout=5)
    public AgentMemory merge(MemoryRequest r,Candidate c) { return mergeLocked(r,c,Decision.fresh()); }
    @Transactional(timeout=5)
    public AgentMemory mergeResolved(MemoryRequest r,Candidate c,Decision decision) { return mergeLocked(r,c,decision); }

    private AgentMemory mergeLocked(MemoryRequest r,Candidate c,Decision decision) {
        MemorySafety.validate(r,c); lock(r.userId());
        String scope=scopeKey(r,c.scope()), hash=hash(MemorySafety.normalized(c.content()));
        String id=UUID.nameUUIDFromBytes((r.userId()+":"+scope+":"+c.type()+":"+hash).getBytes(StandardCharsets.UTF_8)).toString();
        AgentMemory exact=find(r.userId(),id);
        // Tombstones and old revisions are permanent: replay cannot turn any retired row ACTIVE.
        if (exact!=null) return exact.status()==Status.ACTIVE ? mergeDuplicate(r,c,exact) : null;
        if(c.type()!=Type.EPISODIC_TASK && c.type()!=Type.PREVIOUS_RESULT && !jdbc.queryForList("SELECT memory_id FROM agent_memories WHERE user_id=? AND scope_key=? AND type=? AND semantic_key=? AND source_task_id=? AND status<>'ACTIVE' LIMIT 1",
                String.class,r.userId(),scope,c.type().name(),c.semanticKey(),r.taskId()).isEmpty()) return null;
        List<AgentMemory> sameSlot=jdbc.query("""
                SELECT * FROM agent_memories WHERE user_id=? AND scope_key=? AND type=? AND semantic_key=? AND status='ACTIVE'
                """,this::map,r.userId(),scope,c.type().name(),c.semanticKey());
        List<AgentMemory> replace=new ArrayList<>();
        boolean fixed=c.semanticKey().matches("output[.](length|language|format)");
        for (AgentMemory old:sameSlot) {
            if (!compatible(r,c,old)) continue;
            if(fixed && old.metadata().source().equals("USER_FEEDBACK") && !c.metadata().source().equals("USER_FEEDBACK")
                    && !c.metadata().correction()) return old;
            if (fixed && explicitAuthority(c)) replace.add(old);
            else if (old.sourceTaskId().equals(r.taskId()) && Set.of("VERIFIED_TASK","VERIFIED_TASK_RECOVERY").contains(c.metadata().source())) replace.add(old);
        }
        if (decision.targetId()!=null) {
            if(!Double.isFinite(decision.confidence()) || decision.confidence()<.9 || decision.confidence()>1)
                throw new IllegalArgumentException("Invalid semantic confidence");
            AgentMemory target=find(r.userId(),decision.targetId());
            if (target==null || !compatible(r,c,target) || !Objects.equals(target.contentHash(),decision.targetHash()))
                throw new IllegalStateException("Memory relation became stale; retry");
            if (decision.relation()==Relation.DUPLICATE && polarity(c.content())==polarity(target.content())) {
                if(fixed && explicitAuthority(c)) {
                    for(AgentMemory prior:replace) if(!prior.memoryId().equals(target.memoryId())) {
                        jdbc.update("UPDATE agent_memories SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND memory_id=? AND status='ACTIVE'",
                                r.userId(),prior.memoryId()); dirty(r.userId(),prior.memoryId());
                    }
                    jdbc.update("UPDATE agent_memories SET semantic_key=? WHERE user_id=? AND memory_id=?",c.semanticKey(),r.userId(),target.memoryId());
                }
                return mergeDuplicate(r,c,target);
            }
            if ((decision.relation()==Relation.UPDATE || decision.relation()==Relation.CONTRADICTION)
                    && c.metadata().correction() && explicitAuthority(c)) replace.add(target);
        }
        // UNCERTAIN never retires an existing record. Admission is bounded without evicting protected knowledge.
        if (replace.isEmpty() && activeCount(r.userId())>=maxActive) throw new CapacityException();
        insert(r,c,id,scope,hash);
        for (AgentMemory old:replace) {
            jdbc.update("UPDATE agent_memories SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND memory_id=? AND status='ACTIVE'",
                    r.userId(),old.memoryId()); dirty(r.userId(),old.memoryId());
        }
        return find(r.userId(),id);
    }
    private boolean explicitAuthority(Candidate c) {
        return Set.of("RULE","USER_REQUEST","USER_FEEDBACK").contains(c.metadata().source()) && c.metadata().confidence()>=.8;
    }
    private boolean polarity(String text) { return text.matches("(?is).*(不喜欢|不再|不要|不偏好|不习惯|不希望|do not|don't|dislike|no longer).*" ); }
    private AgentMemory mergeDuplicate(MemoryRequest r,Candidate c,AgentMemory old) {
        Set<String> tasks=new LinkedHashSet<>(old.metadata().sourceTaskIds()); tasks.add(r.taskId());
        // Bound active metadata; original job receipts remain available for replay detection.
        if (tasks.size()>64) tasks=new LinkedHashSet<>(tasks.stream().limit(64).toList());
        MemoryMetadata m=new MemoryMetadata(old.metadata().source(),Math.max(old.metadata().confidence(),c.metadata().confidence()),
                old.metadata().quote(),old.metadata().correction(),old.metadata().condition(),old.metadata().topicKey(),old.metadata().level(),
                old.metadata().sourceMemoryIds(),new ArrayList<>(tasks));
        jdbc.update("UPDATE agent_memories SET importance=GREATEST(importance,?),metadata_json=? WHERE user_id=? AND memory_id=? AND status='ACTIVE'",
                c.importance(),JSON.toJSONString(m),r.userId(),old.memoryId());
        return find(r.userId(),old.memoryId());
    }
    private void insert(MemoryRequest r,Candidate c,String id,String scope,String hash) {
        jdbc.update("""
                INSERT INTO agent_memories(memory_id,user_id,type,content,source_task_id,source_video_id,importance,status,
                  scope,scope_key,session_id,semantic_key,content_hash,metadata_json,summary_level)
                VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?,?)
                """,id,r.userId(),c.type().name(),c.content().strip(),r.taskId(),r.videoId(),c.importance(),c.scope().name(),scope,
                r.sessionId(),c.semanticKey(),hash,JSON.toJSONString(c.metadata()),c.metadata().level());
        dirty(r.userId(),id);
    }
    private void dirty(Long user,String id) {
        jdbc.update("""
                INSERT INTO agent_memory_index_outbox(user_id,memory_id) VALUES(?,?)
                ON DUPLICATE KEY UPDATE version=version+1,attempts=0,available_at=CURRENT_TIMESTAMP(3),last_error=NULL
                """,user,id);
    }
    @Transactional(timeout=5)
    public boolean disable(Long user,String id) {
        lock(user);
        int changed=jdbc.update("UPDATE agent_memories SET status='DISABLED',updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND memory_id=?",user,id);
        if(changed>0) dirty(user,id);
        return changed>0;
    }
    public List<String> activeIds(Long user) {
        return jdbc.queryForList("SELECT memory_id FROM agent_memories WHERE user_id=? AND status='ACTIVE'",String.class,user);
    }
    public List<Long> compressionUsers(int threshold) {
        return jdbc.queryForList("SELECT user_id FROM agent_memories WHERE status='ACTIVE' GROUP BY user_id HAVING COUNT(*)>=? LIMIT 10",Long.class,threshold);
    }
    public List<AgentMemory> compressionCandidates(Long user,int ageDays,int limit) {
        return jdbc.query("""
                SELECT * FROM agent_memories WHERE user_id=? AND status='ACTIVE' AND type IN ('EPISODIC_TASK','PREVIOUS_RESULT')
                AND updated_at<=? ORDER BY summary_level DESC,created_at,memory_id LIMIT ?
                """,this::map,user,Timestamp.from(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant().minusSeconds(ageDays*86400L)),limit);
    }
    /** All source validation, links, summary insertion and retirement commit or roll back together. */
    @Transactional(timeout=5)
    public AgentMemory compress(Long user,List<AgentMemory> expected,Candidate summary) {
        lock(user);
        if(expected.size()<2 || expected.size()>16) throw new IllegalArgumentException("Invalid compression batch");
        List<AgentMemory> sources=new ArrayList<>();
        for(AgentMemory old:expected) {
            AgentMemory current=find(user,old.memoryId());
            if(current==null || current.status()!=Status.ACTIVE || !current.contentHash().equals(old.contentHash())) return null;
            sources.add(current);
        }
        AgentMemory first=sources.getFirst();
        if(sources.stream().anyMatch(m -> m.type()!=first.type() || m.scope()!=first.scope()
                || !Objects.equals(m.metadata().topicKey(),first.metadata().topicKey()) || m.metadata().topicKey().isBlank()
                || !Objects.equals(m.sourceVideoId(),first.sourceVideoId()) && first.scope()==Scope.VIDEO
                || !Objects.equals(m.sessionId(),first.sessionId()) && first.scope()==Scope.SESSION
                || !m.metadata().condition().isBlank())) throw new IllegalArgumentException("Incompatible compression sources");
        if(first.type()!=Type.EPISODIC_TASK && first.type()!=Type.PREVIOUS_RESULT) throw new IllegalArgumentException("Protected memory type");
        String content=MemoryConsolidationService.losslessContent(sources);
        if(!content.equals(summary.content()) || summary.type()!=first.type() || summary.scope()!=first.scope()
                || !summary.metadata().sourceMemoryIds().equals(sources.stream().map(AgentMemory::memoryId).toList())
                || summary.metadata().level()!=sources.stream().mapToInt(m -> m.metadata().level()).max().orElse(0)+1)
            throw new IllegalArgumentException("Invalid compression provenance");
        Set<String> expectedTasks=new LinkedHashSet<>();
        for(AgentMemory source:sources) expectedTasks.add(source.sourceTaskId());
        if(!expectedTasks.equals(new LinkedHashSet<>(summary.metadata().sourceTaskIds()))
                || !Objects.equals(summary.metadata().topicKey(),first.metadata().topicKey())
                || !summary.metadata().source().equals("LOSSLESS_COMPRESSION"))
            throw new IllegalArgumentException("Compression lost task provenance");
        MemoryRequest request=new MemoryRequest(user,first.sourceVideoId(),"compression:"+summary.semanticKey(),first.sessionId(),
                new com.example.server.dto.GoalContext(null,null,"长期任务整合"));
        MemorySafety.validate(request,summary);
        String id=UUID.nameUUIDFromBytes((user+":"+summary.semanticKey()).getBytes(StandardCharsets.UTF_8)).toString();
        if(find(user,id)!=null) return null;
        insert(request,summary,id,scopeKey(request,summary.scope()),hash(MemorySafety.normalized(content)));
        for(AgentMemory old:sources) {
            jdbc.update("INSERT INTO agent_memory_sources(user_id,summary_id,source_memory_id,source_task_id) VALUES(?,?,?,?)",
                    user,id,old.memoryId(),old.sourceTaskId());
            jdbc.update("UPDATE agent_memories SET status='COMPRESSED',compressed_into=?,updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND memory_id=? AND status='ACTIVE'",id,user,old.memoryId());
            dirty(user,old.memoryId());
        }
        return find(user,id);
    }
    /** Bounded, resumable reindex: clients paginate by the returned last memory id. */
    @Transactional(timeout=5)
    public List<String> requestReindex(Long user,String after,int limit) {
        lock(user);
        List<String> ids=jdbc.queryForList("SELECT memory_id FROM agent_memories WHERE user_id=? AND memory_id>? ORDER BY memory_id LIMIT ?",
                String.class,user,after==null ? "" : after,Math.clamp(limit,1,200));
        ids.forEach(id -> dirty(user,id)); return ids;
    }
    public List<Map<String,Object>> sourceLinks(Long user,String id) {
        return jdbc.queryForList("SELECT source_memory_id,source_task_id FROM agent_memory_sources WHERE user_id=? AND summary_id=?",user,id);
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private AgentMemory map(ResultSet r,int ignored) throws SQLException {
        String data=r.getString("metadata_json");
        MemoryMetadata m=data==null ? MemoryMetadata.legacy() : JSON.parseObject(data,MemoryMetadata.class);
        return new AgentMemory(r.getString("memory_id"),r.getLong("user_id"),Type.valueOf(r.getString("type")),r.getString("content"),
                r.getString("source_task_id"),r.getObject("source_video_id",Long.class),r.getDouble("importance"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),Status.valueOf(r.getString("status")),
                Scope.valueOf(r.getString("scope")),r.getString("session_id"),r.getString("semantic_key"),r.getString("content_hash"),m);
    }
    public static class CapacityException extends IllegalStateException { public CapacityException(){super("Memory admission deferred: capacity reached");} }
}
