package com.example.server.memory;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.GoalContext;
import com.example.server.utils.AnalysisTaskKeys;

/** Identity is built server-side from owned media, never from a model or a submitted userId. */
public record MemoryRequest(Long userId, Long videoId, String taskId, String sessionId, GoalContext goals,
                            String checkpointKey, boolean trustedUserText) {
    public MemoryRequest(Long userId,Long videoId,String taskId,String sessionId,GoalContext goals) {
        this(userId,videoId,taskId,sessionId,goals,null,true);
    }
    public MemoryRequest withCheckpointGoal(String goal,AnalysisMode mode) {
        return new MemoryRequest(userId,videoId,taskId,sessionId,goals,
                "goal:"+AnalysisTaskKeys.goalDigest(goal,mode)+":result",trustedUserText);
    }
    public static MemoryRequest task(Long userId, Long videoId, String query, String sessionGoal, AnalysisMode mode) {
        return new MemoryRequest(userId, videoId,
                videoId + ":" + AnalysisTaskKeys.goalDigest(query, mode), null,
                new GoalContext(null, sessionGoal, query));
    }
}
