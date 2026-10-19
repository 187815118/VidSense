package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.repository.AgentCheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCheckpointServiceTest {

    @Test void alreadyAppliedLegacyRevisionIsMarkedAndReappliedExactlyOnce() throws Exception {
        var redis=mock(StringRedisTemplate.class);
        var repository=mock(AgentCheckpointRepository.class);
        var mapper=new ObjectMapper();
        var service=new AgentCheckpointService(redis,mapper,repository);
        var plan=new AgentState.AgentPlan("修订目标",List.of("用户明确指定的任务"));
        service.stageRevision(7L,"修订目标",AnalysisMode.GENERAL,plan);
        var original=ArgumentCaptor.forClass(Object.class);
        verify(repository).writeStandalone(eq(7L),anyString(),anyString(),eq("revision"),eq(TaskStage.REVISION_PENDING),original.capture());
        com.fasterxml.jackson.databind.node.ObjectNode legacy=mapper.valueToTree(original.getValue()); legacy.put("applied",true);
        Object payload=mapper.treeToValue(legacy,original.getValue().getClass());
        when(repository.read(anyLong(),anyString(),anyString(),eq("revision"),any(Class.class))).thenReturn(payload);
        assertTrue(service.beginStagedRevision(7L,"修订目标",AnalysisMode.GENERAL));
        verify(repository).write(eq(7L),anyString(),anyString(),anyString(),eq("plan"),eq(TaskStage.PLAN_COMPLETED),eq(plan.withUserRevision()));
        var applied=ArgumentCaptor.forClass(Object.class);
        verify(repository).writeStandalone(eq(7L),anyString(),anyString(),eq("revision"),eq(TaskStage.REVISION_APPLIED),applied.capture());
        when(repository.read(anyLong(),anyString(),anyString(),eq("revision"),any(Class.class))).thenReturn(applied.getValue());
        assertTrue(service.beginStagedRevision(7L,"修订目标",AnalysisMode.GENERAL));
        verify(repository,org.mockito.Mockito.times(1)).deleteByPrefix(eq(7L),anyString());
    }

    @Test
    void persistsRevisionBeforeReplacingGoalCheckpoints() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AgentCheckpointRepository repository = mock(AgentCheckpointRepository.class);
        AgentCheckpointService service =
                new AgentCheckpointService(redis, new ObjectMapper(), repository);
        AgentState.AgentPlan plan = new AgentState.AgentPlan("重新审查", List.of("核验第一条结论"));

        service.stageRevision(7L, "审查视频", AnalysisMode.REVIEW, plan);

        ArgumentCaptor<Object> revision = ArgumentCaptor.forClass(Object.class);
        verify(repository).writeStandalone(
                eq(7L), anyString(), anyString(), eq("revision"),
                eq(TaskStage.REVISION_PENDING), revision.capture());
        when(repository.read(
                anyLong(), anyString(), anyString(), eq("revision"), any(Class.class)))
                .thenReturn(revision.getValue());

        assertTrue(service.beginStagedRevision(7L, "审查视频", AnalysisMode.REVIEW));
        verify(repository).deleteByPrefix(eq(7L), anyString());
        verify(repository).write(
                eq(7L), anyString(), anyString(), anyString(), eq("plan"),
                eq(TaskStage.PLAN_COMPLETED), eq(plan.withUserRevision()));
        verify(repository).writeStandalone(
                eq(7L), anyString(), anyString(), eq("revision"),
                eq(TaskStage.REVISION_APPLIED), any());
    }
}
