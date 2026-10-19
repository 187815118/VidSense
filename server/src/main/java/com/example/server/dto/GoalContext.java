package com.example.server.dto;

/** Long-lived user/session goals require an explicit source; userGoal remains the legacy task field. */
public record GoalContext(String userGoal, String sessionGoal, String currentGoal) {
    public GoalContext {
        userGoal = nullable(userGoal);
        sessionGoal = nullable(sessionGoal);
        currentGoal = currentGoal == null ? "" : currentGoal.trim();
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
