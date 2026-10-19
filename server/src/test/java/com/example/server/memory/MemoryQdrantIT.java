package com.example.server.memory;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.example.server.memory.AgentMemory.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in against disposable services: ./mvnw -Dtest=MemoryQdrantIT -Dmemory.test.qdrant.url=... test */
class MemoryQdrantIT {
    private final String url = System.getProperty("memory.test.qdrant.url", "http://127.0.0.1:16333");
    private final String collection = "memory_test_" + UUID.randomUUID().toString().replace("-", "");

    @Test void actualQdrantEnforcesOwnerScopeAndOwnerBoundCleanup() throws Exception {
        var vectors = new MemoryVectorStore(url, "", collection, "video_chunks", 1000);
        var own = row(1, Scope.GLOBAL_USER, 10);
        var other = row(2, Scope.GLOBAL_USER, 10);
        var video = row(1, Scope.VIDEO, 20);
        try {
            for (AgentMemory memory : List.of(own, other, video)) vectors.upsert(memory, List.of(1.0, 0.0));
            var ownHits = vectors.search(MemoryFlowTest.request(1, 10, "上次", "a"), List.of(1.0, 0.0), 10);
            assertEquals(List.of(own.memoryId()), ownHits.stream().map(MemoryVectorStore.Hit::memoryId).toList());
            assertEquals(2, vectors.search(MemoryFlowTest.request(1, 20, "上次", "a"), List.of(1.0, 0.0), 10).size());
            vectors.retain(1L, List.of());
            assertTrue(vectors.search(MemoryFlowTest.request(1, 20, "上次", "a"), List.of(1.0, 0.0), 10).isEmpty());
            assertEquals(other.memoryId(), vectors.search(MemoryFlowTest.request(2, 10, "上次", "b"), List.of(1.0, 0.0), 10).getFirst().memoryId());
        } finally { dropCollection(); }
    }

    @Test void fullMemoryFlowUsesRealSqlAndQdrantAcrossSessions() throws Exception {
        var fixture = new MemoryFlowTest();
        fixture.setup();
        var vectors = new MemoryVectorStore(url, "", collection, "video_chunks", 1000);
        var service = new MemoryService(true, 5, 1000, new MemoryGate(), fixture.repository, vectors, fixture.embeddings, new MemoryExtractor());
        try {
            var first = MemoryFlowTest.request(1, 10, "我喜欢简短的中文要点，请总结第一章", "session-a");
            service.processCompleted(first, MemoryFlowTest.success(first.goals().currentGoal()));
            fixture.repository.active(first.userId(), first.videoId(), first.sessionId()).forEach(m -> vectors.upsert(m,List.of(.1,.2)));
            var recalled = service.prepare(MemoryFlowTest.request(1, 20, "按照上次继续，按我的习惯总结第二章", "session-b"));
            assertTrue(recalled.memories().stream().anyMatch(m -> m.type() == Type.USER_PREFERENCE));
            assertTrue(recalled.memories().stream().anyMatch(m -> m.type() == Type.EPISODIC_TASK));
            assertTrue(recalled.memories().stream().allMatch(m -> m.userId() == 1));
            assertNull(service.prepare(MemoryFlowTest.request(2, 20, "按照上次继续", "session-b")));
            var preference = recalled.memories().stream().filter(m -> m.type() == Type.USER_PREFERENCE).findFirst().orElseThrow();
            assertTrue(service.disable(1L, preference.memoryId()));
            assertFalse(service.prepare(MemoryFlowTest.request(1, 10, "按我的习惯", "session-c")).memories().stream()
                    .anyMatch(m -> m.memoryId().equals(preference.memoryId())));
        } finally { fixture.close(); dropCollection(); }
    }

    private AgentMemory row(long user, Scope scope, long video) {
        return new AgentMemory(UUID.randomUUID().toString(), user, Type.IMPORTANT_FACT, "测试事实", "test-task", video, .8,
                Instant.now(), Instant.now(), Status.ACTIVE, scope, null, "fact", "hash");
    }

    private void dropCollection() throws Exception {
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url + "/collections/" + collection)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }
}
