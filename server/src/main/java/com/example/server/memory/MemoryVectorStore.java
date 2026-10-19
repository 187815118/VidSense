package com.example.server.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Independent collection; payload is only an index. Content/status/ownership are rechecked in MySQL. */
@Service
public class MemoryVectorStore {
    private final String url;
    private final String collection;
    private final String apiKey;
    private final OkHttpClient client;
    private final boolean validCollection;
    private volatile boolean ready;
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    public MemoryVectorStore(@Value("${vector.qdrant.url:http://localhost:6333}") String url,
                             @Value("${vector.qdrant.api-key:}") String apiKey,
                             @Value("${memory.qdrant.collection:agent_memories}") String collection,
                             @Value("${vector.qdrant.collection:video_chunks}") String videoCollection,
                             @Value("${memory.io-timeout-ms:1500}") long timeoutMs) {
        this.url = url.replaceAll("/+$", "");
        this.collection = collection;
        this.apiKey = apiKey;
        this.validCollection = collection.matches("[A-Za-z0-9_-]{1,128}") && !collection.equals(videoCollection);
        this.client = new OkHttpClient.Builder().callTimeout(Duration.ofMillis(Math.clamp(timeoutMs, 100, 5000))).build();
    }

    public List<Hit> search(MemoryRequest request, List<Double> vector, int limit) {
        ensureCollection(vector.size());
        List<Object> scopes = new ArrayList<>();
        scopes.add(match("scope", "GLOBAL_USER"));
        if (request.videoId() != null) scopes.add(Map.of("must", List.of(match("scope", "VIDEO"), match("sourceVideoId", request.videoId()))));
        if (request.sessionId() != null) scopes.add(Map.of("must", List.of(match("scope", "SESSION"), match("sessionId", request.sessionId()))));
        Map<String, Object> filter = Map.of("must", List.of(match("userId", request.userId())), "should", scopes);
        JSONObject json = call("POST", "/points/query", Map.of("query", vector, "limit", limit,
                "filter", filter, "with_payload", true));
        JSONObject result = json.getJSONObject("result");
        JSONArray points = result == null ? null : result.getJSONArray("points");
        if (points == null) return List.of();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            JSONObject point = points.getJSONObject(i);
            JSONObject payload = point.getJSONObject("payload");
            if (payload != null && java.util.Objects.equals(payload.getLong("userId"), request.userId())) {
                hits.add(new Hit(point.getString("id"), point.getDoubleValue("score"), payload.getString("contentHash")));
            }
        }
        return hits;
    }

    public void upsert(AgentMemory memory, List<Double> vector) {
        ensureCollection(vector.size());
        JSONObject payload = new JSONObject();
        payload.put("userId", memory.userId());
        payload.put("scope", memory.scope().name());
        payload.put("sourceVideoId", memory.sourceVideoId());
        payload.put("sessionId", memory.sessionId());
        payload.put("contentHash", memory.contentHash());
        call("PUT", "/points?wait=true", Map.of("points", List.of(
                Map.of("id", memory.memoryId(), "vector", vector, "payload", payload))));
    }

    /** Also removes superseded/retired vectors; an outage leaves only harmless stale index entries. */
    public void delete(Long userId, String id) {
        if (!validCollection) throw new IllegalStateException("Invalid Memory collection");
        call("POST", "/points/delete?wait=true", Map.of("filter", Map.of("must", List.of(
                match("userId", userId), Map.of("has_id", List.of(id))))));
    }

    public void retain(Long userId, List<String> activeIds) {
        if (!ready) return;
        Map<String, Object> filter = activeIds.isEmpty()
                ? Map.of("must", List.of(match("userId", userId)))
                : Map.of("must", List.of(match("userId", userId)), "must_not", List.of(Map.of("has_id", activeIds)));
        call("POST", "/points/delete?wait=true", Map.of("filter", filter));
    }

    private synchronized void ensureCollection(int size) {
        if (!validCollection) throw new IllegalStateException("Memory requires a distinct valid Qdrant collection");
        if (size < 1) throw new IllegalArgumentException("Empty memory embedding");
        if (ready) return;
        Request.Builder lookup = request("").get();
        try (Response response = client.newCall(lookup.build()).execute()) {
            if (response.isSuccessful()) {
                JSONObject info = JSON.parseObject(response.body().string());
                int actual = info.getJSONObject("result").getJSONObject("config").getJSONObject("params")
                        .getJSONObject("vectors").getIntValue("size");
                if (actual != size) throw new IllegalStateException("Memory embedding dimension differs from existing collection");
                ready = true;
                return;
            }
            if (response.code() != 404) throw new IllegalStateException("Memory Qdrant lookup failed: " + response.code());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Memory Qdrant unavailable", e);
        }
        call("PUT", "", Map.of("vectors", Map.of("size", size, "distance", "Cosine")));
        ready = true;
    }

    private Object match(String key, Object value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private Request.Builder request(String suffix) {
        Request.Builder request = new Request.Builder().url(url + "/collections/" + collection + suffix);
        if (!apiKey.isBlank()) request.header("api-key", apiKey);
        return request;
    }

    private JSONObject call(String method, String suffix, Object body) {
        Request request = request(suffix).method(method, RequestBody.create(JSON.toJSONString(body), JSON_TYPE)).build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 404 && suffix.startsWith("/points/delete")) {
                ready = false;
                return new JSONObject(); // deleting from a lost collection is already satisfied
            }
            if (!response.isSuccessful() || response.body() == null) {
                ready = false;
                throw new IllegalStateException("Memory Qdrant request failed: " + response.code());
            }
            return JSON.parseObject(response.body().string());
        } catch (java.io.IOException e) {
            ready = false;
            throw new IllegalStateException("Memory Qdrant unavailable", e);
        }
    }

    public record Hit(String memoryId, double similarity, String contentHash) { }

}
