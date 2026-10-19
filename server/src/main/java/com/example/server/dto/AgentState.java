package com.example.server.dto;

import java.util.List;

/**
 * 受控 AgentLoop 的显式状态：目标、计划、当前产物、Critic 反馈和轮次。
 */
public record AgentState(
        String goal,
        AgentPlan plan,
        AnalysisResult result,
        CriticResult critique,
        int round
) {
    public AgentState {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("agent goal is required");
        if (round < 0) throw new IllegalArgumentException("agent round cannot be negative");
        goal = goal.trim();
    }

    public record AgentPlan(
            String understoodGoal,
            List<String> tasks,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
            boolean memoryAugmented,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
            boolean userRevised
    ) {
        public AgentPlan(String understoodGoal, List<String> tasks) {
            this(understoodGoal, tasks, false, false);
        }
        public AgentPlan(String goal,List<String> tasks,boolean memoryAugmented) {
            this(goal,tasks,memoryAugmented,false);
        }

        public AgentPlan withMemoryProvenance() {
            return new AgentPlan(understoodGoal, tasks, true, userRevised);
        }
        public AgentPlan withUserRevision() {
            return new AgentPlan(understoodGoal,tasks,memoryAugmented,true);
        }
        public AgentPlan {
            understoodGoal = understoodGoal == null ? "" : understoodGoal.trim();
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    public record CriticResult(
            boolean passed,
            List<String> feedback,
            List<String> missingRequirements,
            List<String> unsupportedClaims,
            List<Long> requiredTimestamps
    ) {
        public CriticResult {
            feedback = feedback == null ? List.of() : List.copyOf(feedback);
            missingRequirements = missingRequirements == null ? List.of() : List.copyOf(missingRequirements);
            unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
            requiredTimestamps = requiredTimestamps == null ? List.of() : List.copyOf(requiredTimestamps);
        }
    }
}
