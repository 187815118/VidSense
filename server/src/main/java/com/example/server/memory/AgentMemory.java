package com.example.server.memory;

import java.time.Instant;

/** Durable, user-owned knowledge. Never contains a running AgentState or a checkpoint payload. */
public record AgentMemory(String memoryId, Long userId, Type type, String content,
                          String sourceTaskId, Long sourceVideoId, double importance,
                          Instant createdAt, Instant updatedAt, Status status,
                          Scope scope, String sessionId, String semanticKey, String contentHash,
                          MemoryMetadata metadata) {
    public AgentMemory(String id, Long user, Type type, String content, String task, Long video, double importance,
                       Instant created, Instant updated, Status status, Scope scope, String session,
                       String key, String hash) {
        this(id, user, type, content, task, video, importance, created, updated, status, scope, session, key, hash,
                MemoryMetadata.legacy());
    }
    public enum Type { USER_PREFERENCE, EPISODIC_TASK, IMPORTANT_FACT, PREVIOUS_RESULT, USER_FEEDBACK }
    public enum Scope { GLOBAL_USER, VIDEO, SESSION }
    public enum Status { ACTIVE, DISABLED, SUPERSEDED, COMPRESSED }

    public record Candidate(Type type, String content, double importance, Scope scope, String semanticKey,
                            MemoryMetadata metadata) {
        public Candidate(Type type, String content, double importance, Scope scope, String semanticKey) {
            this(type, content, importance, scope, semanticKey, MemoryMetadata.legacy());
        }
    }
}
