package com.example.server.memory;

import com.example.server.dto.GoalContext;
import java.util.List;

/** Request-local auxiliary context. Excluded from VideoContext JSON/checkpoint persistence. */
public record MemoryContext(GoalContext goals, List<AgentMemory> memories) {
    public MemoryContext {
        memories = memories == null ? List.of() : List.copyOf(memories);
    }
}
