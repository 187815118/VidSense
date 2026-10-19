package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.memory.MemoryService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisStatusServiceTest {
    private final AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    private final AnalysisDispatchService dispatch = mock(AnalysisDispatchService.class);
    private final MemoryService memory = mock(MemoryService.class);
    private final AnalysisStatusService status = new AnalysisStatusService(checkpoints, dispatch, memory);

    @Test void activeMemoryRefreshDoesNotExposeAnOldCompletedResult() {
        when(memory.enabled()).thenReturn(true);
        when(checkpoints.loadResult(1L, "goal", AnalysisMode.GENERAL)).thenReturn(result());
        when(checkpoints.loadStage(1L, "goal", AnalysisMode.GENERAL)).thenReturn(TaskStage.CRITIC_PASSED);
        when(dispatch.isActive(1L, "goal", AnalysisMode.GENERAL)).thenReturn(true);
        assertEquals(TaskStatus.State.PROCESSING, status.current(1L, "goal").state());
    }

    @Test void completedMemoryTaskBecomesVisibleAfterActiveWorkFinishes() {
        when(memory.enabled()).thenReturn(true);
        when(checkpoints.loadResult(1L, "goal", AnalysisMode.GENERAL)).thenReturn(result());
        assertEquals(TaskStatus.State.COMPLETED, status.current(1L, "goal").state());
    }

    @Test void disabledMemoryPreservesLegacyCompletedResultBehavior() {
        when(checkpoints.loadResult(1L, "goal", AnalysisMode.GENERAL)).thenReturn(result());
        assertEquals(TaskStatus.State.COMPLETED, status.current(1L, "goal").state());
        verifyNoInteractions(dispatch);
    }

    @Test void progressCacheFailureStillReturnsTheDurableResult() {
        when(memory.enabled()).thenReturn(true);
        when(checkpoints.loadResult(1L, "goal", AnalysisMode.GENERAL)).thenReturn(result());
        when(dispatch.isActive(1L, "goal", AnalysisMode.GENERAL)).thenThrow(new IllegalStateException("redis unavailable"));
        assertEquals(TaskStatus.State.COMPLETED, status.current(1L, "goal").state());
    }

    private AgentState result() {
        return new AgentState("goal", new AgentState.AgentPlan("goal", List.of("分析")),
                new AnalysisResult("标题", List.of("结论"), List.of(), List.of(), List.of()),
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()), 1);
    }
}
