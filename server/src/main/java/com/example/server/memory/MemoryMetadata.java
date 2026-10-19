package com.example.server.memory;

import java.util.List;

/** Server-validated provenance, never an authority for video evidence. */
public record MemoryMetadata(String source, double confidence, String quote, boolean correction,
                             String condition, String topicKey, int level,
                             List<String> sourceMemoryIds, List<String> sourceTaskIds) {
    public MemoryMetadata {
        sourceMemoryIds = sourceMemoryIds == null ? List.of() : List.copyOf(sourceMemoryIds);
        sourceTaskIds = sourceTaskIds == null ? List.of() : List.copyOf(sourceTaskIds);
        condition = condition == null ? "" : condition;
        quote = quote == null ? "" : quote;
        topicKey = topicKey == null ? "" : topicKey;
    }

    public static MemoryMetadata legacy() {
        return new MemoryMetadata("RULE", 1, "", false, "", "", 0, List.of(), List.of());
    }
}
