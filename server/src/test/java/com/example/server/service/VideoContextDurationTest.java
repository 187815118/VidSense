package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.OcrUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoContextDurationTest {
    @Test void legacyMinuteWindowCannotSupportThirtySecondsInTwelveSecondVideo() {
        var original = context(0, 60000);
        var bounded = VideoContextService.clipToDuration(original, 12000);
        var verifier = new EvidenceVerificationService();
        assertEquals(12000, bounded.segments().getFirst().endMs());
        assertFalse(verifier.supported(bounded, new AnalysisResult.Evidence(30000, "ASR", "five red squares", "五个红色正方形")));
        assertTrue(verifier.supported(bounded, new AnalysisResult.Evidence(0, "ASR", "five red squares", "五个红色正方形")));
        assertEquals(60000, original.segments().getFirst().endMs());
        assertEquals(original.segments().getFirst().evidenceFrames(), bounded.segments().getFirst().evidenceFrames());
    }

    @Test void trimsLastLongVideoWindowAndDropsWindowsPastActualEnd() {
        var original = new VideoContext("video.mp4", "goal", List.of(
                context(0, 60000).segments().getFirst(), context(60000, 120000).segments().getFirst(),
                context(120000, 180000).segments().getFirst()));
        var bounded = VideoContextService.clipToDuration(original, 72000);
        assertEquals(2, bounded.segments().size());
        assertEquals(72000, bounded.segments().getLast().endMs());
        assertThrows(IllegalArgumentException.class, () -> VideoContextService.clipToDuration(original, 0));
    }

    @Test void probesRealMediaDuration(@TempDir Path temporary) throws Exception {
        Path video = temporary.resolve("one-second.mp4");
        Process ffmpeg = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                "color=c=black:s=16x16:d=1", "-an", "-c:v", "libx264", video.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(ffmpeg.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, ffmpeg.exitValue());
        var minio = mock(MinioUtils.class);
        when(minio.readableSource("video.mp4")).thenReturn(video.toString());
        var service = new VideoContextService(mock(SegmentedTranscriptionService.class), mock(OcrUtils.class), minio,
                mock(ThreadPoolTaskExecutor.class), mock(ThreadPoolTaskExecutor.class), mock(AgentTelemetry.class));
        var bounded = service.boundToSource(context(0, 60000));
        assertEquals(1000, bounded.segments().getFirst().endMs());
    }

    private VideoContext context(long start, long end) {
        return new VideoContext("video.mp4", "goal", List.of(
                new VideoContext.VideoSegment(start, end, "five red squares", List.of("FIVE RED SQUARES"), List.of("frame.jpg"))));
    }
}
