package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.memory.MemoryService;
import com.example.server.service.mode.ModeRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiServiceFollowUpTest {
    @Test void explicitUserRevisionCannotBeReusedAsAnotherVideosGenericResult() {
        var mapper=mock(MediaFileMapper.class);
        var checkpoint=mock(AgentCheckpointService.class);
        var memory=mock(MemoryService.class);
        var service=new AiService(mapper,mock(VideoContextService.class),mock(LongVideoContextService.class),
                mock(AgentLoopService.class),checkpoint,mock(AgentTelemetry.class),mock(MediaService.class),
                mock(TaskEventService.class),mock(RedissonClient.class),mock(StringRedisTemplate.class),mock(ModeRegistry.class),memory);
        var state=new AgentState("分析视频",new AgentState.AgentPlan("分析视频",List.of("用户指定任务")).withUserRevision(),result(),
                new AgentState.CriticResult(true,List.of(),List.of(),List.of(),List.of()),1);
        assertFalse(service.reuseResult(11L,10L,state,AnalysisMode.GENERAL));
        verifyNoInteractions(mapper,checkpoint);
    }
    @Test void rejectedFollowUpKeepsTheExistingCriticWarning() {
        String answer = followUp(false);
        assertTrue(answer.startsWith("> **结果提示：**"));
        assertTrue(answer.contains("未通过 Critic 校验"));
    }

    @Test void passedFollowUpKeepsItsOriginalMarkdown() {
        assertEquals(result().toMarkdown(), followUp(true));
    }

    private String followUp(boolean passed) {
        var checkpoint = mock(AgentCheckpointService.class);
        var loop = mock(AgentLoopService.class);
        when(checkpoint.loadContext(10L)).thenReturn(new VideoContext("video.mp4", "", List.of(
                new VideoContext.VideoSegment(0, 1000, "FIVE RED SQUARES", List.of(), List.of()))));
        when(loop.run(eq(10L), any(VideoContext.class), any(), any())).thenReturn(new AgentState(
                "继续", new AgentState.AgentPlan("核验数量颜色", List.of("引用当前证据")), result(),
                new AgentState.CriticResult(passed, passed ? List.of() : List.of("引文无法核验"), List.of(), List.of(), List.of()), 1));
        var videoContextService = mock(VideoContextService.class);
        when(videoContextService.boundToSource(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new AiService(mock(MediaFileMapper.class), videoContextService,
                mock(LongVideoContextService.class), loop, checkpoint, mock(AgentTelemetry.class),
                mock(MediaService.class), mock(TaskEventService.class), mock(RedissonClient.class),
                mock(StringRedisTemplate.class), mock(ModeRegistry.class), mock(MemoryService.class));
        return service.followUp(10L, null, "继续", AnalysisMode.GENERAL);
    }

    private AnalysisResult result() {
        return new AnalysisResult("数量颜色", List.of("五个红色正方形"),
                List.of(new AnalysisResult.Evidence(0, "ASR", "FIVE RED SQUARES", "五个红色正方形")), List.of(), List.of());
    }
}
