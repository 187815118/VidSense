package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.memory.AgentMemory;
import com.example.server.memory.MemoryRepository;
import com.example.server.memory.MemoryService;
import com.example.server.service.AuthService;
import com.example.server.service.MediaService;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

/** Read-only owner-scoped workspace view; does not run retrieval, embedding or extraction. */
@RestController
@RequestMapping("/analysis/memory")
public class MemoryReadController {
    private final MemoryService memory;
    private final MemoryRepository repository;
    private final MediaService media;
    public MemoryReadController(MemoryService memory, MemoryRepository repository, MediaService media) {
        this.memory=memory; this.repository=repository; this.media=media;
    }
    @GetMapping
    public Result<Overview> overview(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
                                   @RequestParam(required=false) Long videoId,
                                   @RequestParam(defaultValue="20") int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (videoId!=null) media.requireOwnedMedia(videoId,userId);
        if (!memory.enabled()) return Result.ok(new Overview(false,List.of()));
        // A client cannot select another user's identity or inject a Session ID.
        var rows=repository.active(userId,videoId,null).stream().limit(limit).map(Item::from).toList();
        return Result.ok(new Overview(true,rows));
    }
    public record Overview(boolean enabled,List<Item> items) { }
    public record Item(String memoryId,AgentMemory.Type type,String content,AgentMemory.Scope scope,
                       Long sourceVideoId,String sourceTaskId,Instant updatedAt) {
        static Item from(AgentMemory m) {
            return new Item(m.memoryId(),m.type(),m.content(),m.scope(),m.sourceVideoId(),m.sourceTaskId(),m.updatedAt());
        }
    }
}
