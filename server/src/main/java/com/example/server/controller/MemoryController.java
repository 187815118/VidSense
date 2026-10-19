package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.memory.MemoryService;
import com.example.server.service.AuthService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/analysis/memory")
public class MemoryController {
    private final MemoryService memory;

    public MemoryController(MemoryService memory) { this.memory = memory; }
    @PostMapping("/retry")
    public Result<Integer> retry(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(memory.retryFailed(userId));
    }

    /** Paginated owner-only rebuild, also removes indexed tombstones after a collection restore. */
    @PostMapping("/reindex")
    public Result<java.util.List<String>> reindex(@RequestParam(required=false) String after,
                                                 @RequestParam(defaultValue="100") int limit,
                                                 @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(memory.requestReindex(userId,after,limit));
    }

    @GetMapping("/{memoryId}/sources")
    public Result<java.util.List<java.util.Map<String,Object>>> sources(@PathVariable String memoryId,
                                                    @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(memory.sources(userId,memoryId));
    }

    @DeleteMapping("/{memoryId}")
    public Result<Boolean> disable(@PathVariable String memoryId,
                                   @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(memory.disable(userId, memoryId));
    }
}
