package com.example.server.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static com.example.server.memory.AgentMemory.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryVectorStoreTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    @Test void queriesIndependentCollectionWithUserAndScopeFiltersAndRejectsForeignPayload() throws Exception {
        AtomicReference<JSONObject> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/agent_memories", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/points/query")) {
                query.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                reply(exchange, 200, """
                        {"result":{"points":[
                        {"id":"11111111-1111-1111-1111-111111111111","score":0.9,"payload":{"userId":1,"contentHash":"hash"}},
                        {"id":"22222222-2222-2222-2222-222222222222","score":1,"payload":{"userId":2,"contentHash":"hash"}}
                        ]}}
                        """);
            } else reply(exchange, 200, "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":2}}}}}");
        });
        server.start();
        try {
            var vectors = new MemoryVectorStore("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "agent_memories", "video_chunks", 1000);
            var hits = vectors.search(MemoryRequest.task(1L, 10L, "上次", null, null), List.of(1.0, 0.0), 10);
            assertEquals(1, hits.size());
            assertEquals(ID, hits.getFirst().memoryId());
            assertEquals("userId", query.get().getJSONObject("filter").getJSONArray("must").getJSONObject(0).getString("key"));
            assertEquals(1, query.get().getJSONObject("filter").getJSONArray("must").getJSONObject(0).getJSONObject("match").getIntValue("value"));
            assertEquals(2, query.get().getJSONObject("filter").getJSONArray("should").size());
        } finally { server.stop(0); }
    }

    @Test void createsCollectionLazilyUpsertsOnlyMetadataAndCleansRetiredVectorsWithinOwner() throws Exception {
        AtomicReference<JSONObject> upsert = new AtomicReference<>();
        AtomicReference<JSONObject> cleanup = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/agent_memories", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("GET")) { reply(exchange, 404, "{}"); return; }
            JSONObject body = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (path.endsWith("/points")) upsert.set(body);
            if (path.endsWith("/points/delete")) cleanup.set(body);
            reply(exchange, 200, "{\"result\":true}");
        });
        server.start();
        try {
            var vectors = new MemoryVectorStore("http://127.0.0.1:" + server.getAddress().getPort(), "", "agent_memories", "video_chunks", 1000);
            var memory = new AgentMemory(ID, 1L, Type.USER_PREFERENCE, "用户偏好中文", "task", 10L, .8,
                    Instant.now(), Instant.now(), Status.ACTIVE, Scope.GLOBAL_USER, null, "language", "hash");
            vectors.upsert(memory, List.of(1.0, 0.0));
            JSONObject payload = upsert.get().getJSONArray("points").getJSONObject(0).getJSONObject("payload");
            assertEquals(1, payload.getLongValue("userId"));
            assertFalse(payload.containsKey("content"));
            vectors.retain(1L, List.of(ID));
            assertEquals(ID, cleanup.get().getJSONObject("filter").getJSONArray("must_not").getJSONObject(0).getJSONArray("has_id").getString(0));
            assertEquals(1, cleanup.get().getJSONObject("filter").getJSONArray("must").getJSONObject(0).getJSONObject("match").getLongValue("value"));
        } finally { server.stop(0); }
    }

    @Test void actualHttpFailurePropagatesToFailOpenBoundary() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> reply(exchange, 503, "{}"));
        server.start();
        try {
            var vectors = new MemoryVectorStore("http://127.0.0.1:" + server.getAddress().getPort(), "", "agent_memories", "video_chunks", 1000);
            assertThrows(IllegalStateException.class, () -> vectors.search(
                    MemoryRequest.task(1L, 10L, "之前", null, null), List.of(1.0, 0.0), 5));
        } finally { server.stop(0); }
    }

    @Test void refusesToShareTheVideoEvidenceCollection() {
        var vectors = new MemoryVectorStore("http://localhost:6333", "", "video_chunks", "video_chunks", 1000);
        assertThrows(IllegalStateException.class, () -> vectors.search(
                MemoryRequest.task(1L, 10L, "之前", null, null), List.of(1.0, 0.0), 5));
    }

    private static void reply(HttpExchange exchange, int status, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
