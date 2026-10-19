package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.memory.*;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.time.Instant;
import java.util.List;
import static com.example.server.memory.AgentMemory.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopMemoryTest {
    private static final VideoContext VIDEO = new VideoContext("video.mp4", "按照上次继续核验人数", List.of(
            new VideoContext.VideoSegment(0, 1000, "视频中有两个人", List.of(), List.of())));
    private static final MemoryRequest REQUEST = MemoryRequest.task(1L, 10L, VIDEO.userGoal(), null, AnalysisMode.GENERAL);
    private final DeepSeekUtils model = mock(DeepSeekUtils.class);
    private final LongVideoContextService retrieval = mock(LongVideoContextService.class);
    private final AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    private final AgentTelemetry telemetry = mock(AgentTelemetry.class);
    private final TaskEventService events = mock(TaskEventService.class);
    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final MemoryVectorStore vectors = mock(MemoryVectorStore.class);
    private final EmbeddingUtils embeddings = mock(EmbeddingUtils.class);
    private final MemoryExtractor extractor = spy(new MemoryExtractor());
    private final MemoryJobRepository jobs=mock(MemoryJobRepository.class);

    private AgentLoopService loop(boolean enabled, AnalysisResult result) {
        return loop(enabled, result, vectors);
    }

    private AgentLoopService loop(boolean enabled, AnalysisResult result, MemoryVectorStore memoryVectors) {
        when(telemetry.currentUsage()).thenReturn(new AgentTelemetry.BudgetUsage(0, 0));
        when(retrieval.selectRelevant(any(), any())).thenAnswer(call -> call.getArgument(1));
        when(model.plan(any(), anyString())).thenReturn(new AgentState.AgentPlan(VIDEO.userGoal(), List.of("核验视频中的人数")));
        when(model.execute(any(), any(), any(), anyString())).thenReturn(result);
        when(model.critique(any(), any(), any(), anyString())).thenReturn(new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()));
        when(embeddings.embed(anyString(), anyLong())).thenReturn(List.of(.1, .2));
        var memory = new MemoryService(enabled, 5, 100, new MemoryGate(), repository, memoryVectors, embeddings, extractor);
        memory.configureBackground(new MemoryExtractionService(extractor,null,true),null,jobs,null);
        return new AgentLoopService(model, retrieval, checkpoints, telemetry, new EvidenceVerificationService(), events,
                memory, 1, 10000, 50000, 0);
    }

    private static AnalysisResult result(String count) {
        String claim = "视频中有" + count + "个人";
        return new AnalysisResult("人数核验", List.of(claim),
                List.of(new AnalysisResult.Evidence(100, "ASR", claim, claim)), List.of(), List.of());
    }

    @Test void qdrantFailureStillRunsOriginalVideoRetrievalPlannerExecutorAndCritic() {
        var loop = loop(true, result("两"));
        when(vectors.search(any(), anyList(), anyInt())).thenThrow(new IllegalStateException("Qdrant is down"));
        AgentState state = loop.run(10L, VIDEO, null, REQUEST);
        assertTrue(state.critique().passed());
        assertEquals("视频中有两个人", state.result().conclusions().getFirst());
        verify(retrieval).selectRelevant(eq(10L), any());
        verify(model).plan(any(), anyString());
        verify(model).execute(any(), any(), any(), anyString());
        verify(model).critique(any(), any(), any(), anyString());
        verify(checkpoints).saveResult(10L, state, AnalysisMode.GENERAL);
    }

    @Test void actualMemoryQdrantHttp503DoesNotFailTheAgent() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var actualVectors = new MemoryVectorStore("http://127.0.0.1:" + server.getAddress().getPort(), "", "agent_memories", "video_chunks", 1000);
            var state = loop(true, result("两"), actualVectors).run(10L, VIDEO, null, REQUEST);
            assertTrue(state.critique().passed());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test void rememberedVideoClaimCannotBeUsedAsCurrentVideoEvidence() {
        var loop = loop(true, result("五"));
        AgentMemory old = oldMemory();
        when(repository.active(1L, 10L, null)).thenReturn(List.of(old));
        when(vectors.search(any(), anyList(), anyInt())).thenReturn(List.of(new MemoryVectorStore.Hit(old.memoryId(), .99, old.contentHash())));
        AgentState state = loop.run(10L, VIDEO, null, REQUEST);
        assertFalse(state.critique().passed());
        assertFalse(state.critique().unsupportedClaims().isEmpty());
        verify(model).plan(argThat(c -> c.memory() != null && c.memory().memories().contains(old)), anyString());
        verifyNoInteractions(extractor);
        verify(repository, never()).merge(any(), any());
    }

    @Test void disabledFeatureRestoresLegacyContextAndFinishedCheckpointReuse() throws Exception {
        var loop = loop(false, result("两"));
        AgentState saved = new AgentState(VIDEO.userGoal(), new AgentState.AgentPlan(VIDEO.userGoal(), List.of("核验人数")), result("两"),
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()), 1);
        when(checkpoints.loadCriticState(10L, VIDEO.userGoal(), AnalysisMode.GENERAL)).thenReturn(saved);
        assertSame(saved, loop.run(10L, VIDEO, null, REQUEST));
        verifyNoInteractions(retrieval, model, repository, vectors, embeddings, extractor);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var oldPlan = mapper.readValue("{\"understoodGoal\":\"old goal\",\"tasks\":[\"task\"]}", AgentState.AgentPlan.class);
        assertFalse(oldPlan.memoryAugmented());
        assertEquals(2, mapper.readTree(mapper.writeValueAsString(oldPlan)).size());
        assertEquals(3, mapper.readTree(mapper.writeValueAsString(VIDEO)).size());
        assertFalse(mapper.writeValueAsString(VIDEO.withMemory(new MemoryContext(REQUEST.goals(), List.of(oldMemory())))).contains("记忆中有五个人"));
    }

    @Test void enabledFeatureRefreshesFinishedResultsButRetainsExecutionCheckpointSupport() {
        var loop = loop(true, result("两"));
        recallOldMemory();
        AgentState saved = new AgentState(VIDEO.userGoal(), new AgentState.AgentPlan(VIDEO.userGoal(), List.of("旧计划")), result("五"),
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()), 1);
        when(checkpoints.loadCriticState(10L, VIDEO.userGoal(), AnalysisMode.GENERAL)).thenReturn(saved);
        when(checkpoints.loadResult(10L, VIDEO.userGoal(), AnalysisMode.GENERAL)).thenReturn(saved);
        AgentState state = loop.run(10L, VIDEO, null, REQUEST);
        assertNotSame(saved, state);
        assertEquals(result("两"), state.result());
        verify(model).plan(any(), anyString());
    }

    @Test void persistsCompletionBeforeQueuingOptionalMemoryWork() {
        AgentState state = loop(true, result("两")).run(10L, VIDEO, null, REQUEST);
        var order = inOrder(jobs, checkpoints);
        order.verify(checkpoints).saveResult(10L, state, AnalysisMode.GENERAL);
        order.verify(jobs).enqueue(any());
    }

    @Test void terminalCriticWithoutPublishedResultResumesPendingMemoryUpdate() {
        var loop = loop(true, result("两"));
        recallOldMemory();
        var plan = new AgentState.AgentPlan(VIDEO.userGoal(), List.of("核验人数")).withMemoryProvenance();
        var saved = new AgentState(VIDEO.userGoal(), plan, result("两"),
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()), 1);
        when(checkpoints.loadCriticState(10L, VIDEO.userGoal(), AnalysisMode.GENERAL)).thenReturn(saved);
        assertSame(saved, loop.run(10L, VIDEO, null, REQUEST));
        verifyNoInteractions(model, retrieval);
        var order = inOrder(jobs, checkpoints);
        order.verify(checkpoints).saveResult(10L, saved, AnalysisMode.GENERAL);
        order.verify(jobs).enqueue(any());
    }

    @Test void inFlightExecutorCheckpointResumesAtCriticWithMemoryEnabled() {
        var loop = loop(true, result("两"));
        recallOldMemory();
        AgentState draft = new AgentState(VIDEO.userGoal(), new AgentState.AgentPlan(VIDEO.userGoal(), List.of("核验人数")), result("两"), null, 1);
        when(checkpoints.loadCriticState(10L, VIDEO.userGoal(), AnalysisMode.GENERAL)).thenReturn(draft);
        assertTrue(loop.run(10L, VIDEO, null, REQUEST).critique().passed());
        verify(model, never()).execute(any(), any(), any(), anyString());
        verify(model, never()).plan(any(), anyString());
        verify(model).critique(any(), eq(draft.plan().withMemoryProvenance()), eq(draft.result()), anyString());
    }

    @Test void disablingMemoryDoesNotReuseAnEarlierPersonalizedCheckpointOrPlan() {
        var loop = loop(false, result("两"));
        var personalized = new AgentState.AgentPlan(VIDEO.userGoal(), List.of("旧个性化计划")).withMemoryProvenance();
        var saved = new AgentState(VIDEO.userGoal(), personalized, result("五"),
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()), 1);
        when(checkpoints.loadCriticState(10L, VIDEO.userGoal(), AnalysisMode.GENERAL)).thenReturn(saved);
        when(checkpoints.loadPlan(10L, VIDEO.userGoal(), AnalysisMode.GENERAL)).thenReturn(personalized);
        AgentState refreshed = loop.run(10L, VIDEO, null, REQUEST);
        assertEquals(result("两"), refreshed.result());
        assertFalse(refreshed.plan().memoryAugmented());
        verify(model).plan(any(), anyString());
        verifyNoInteractions(repository, vectors, embeddings, extractor);
    }

    @Test void promptSeparatesTaskMemoryAndEvidenceAndGivesVideoPrecedence() throws Exception {
        var renderer = new DeepSeekUtils("test", "http://127.0.0.1:1", "test", 1, 0, 0, 0,
                telemetry, new ObjectMapper().findAndRegisterModules(), mock(ThreadPoolTaskExecutor.class));
        String prompt = renderer.renderContext(VIDEO.withMemory(new MemoryContext(REQUEST.goals(), List.of(oldMemory()))));
        assertTrue(prompt.indexOf("Current Task") < prompt.indexOf("Agent Memory"));
        assertTrue(prompt.indexOf("Agent Memory") < prompt.indexOf("Video Evidence"));
        assertTrue(prompt.contains("必须以 Video Evidence 为准"));
        assertTrue(prompt.contains("记忆中有五个人"));
        assertTrue(prompt.contains("视频中有两个人"));
        assertEquals(new ObjectMapper().writeValueAsString(VIDEO), renderer.renderContext(VIDEO));
    }

    private void recallOldMemory() {
        AgentMemory m=oldMemory();
        when(repository.active(1L,10L,null)).thenReturn(List.of(m));
        when(vectors.search(any(),anyList(),anyInt())).thenReturn(List.of(new MemoryVectorStore.Hit(m.memoryId(),.99,m.contentHash())));
    }
    @Test void emptyRecallDoesNotMarkPlanAsMemoryAugmentedOrCallExtraModels() {
        var loop=loop(true,result("两"));
        when(model.plan(any(),anyString())).thenReturn(new AgentState.AgentPlan(VIDEO.userGoal(),List.of("核验人数"),true,true));
        AgentState state=loop.run(10L,VIDEO,null,REQUEST);
        assertFalse(state.plan().memoryAugmented());
        assertFalse(state.plan().userRevised());
        verify(model,times(1)).plan(any(),anyString());
    }
    @Test void explicitUserRevisedPlanWinsWithRealMemoryRecall() {
        var loop=loop(true,result("两")); recallOldMemory();
        var revised=new AgentState.AgentPlan(VIDEO.userGoal(),List.of("只执行用户指定任务")).withUserRevision();
        when(checkpoints.loadPlan(10L,VIDEO.userGoal(),AnalysisMode.GENERAL)).thenReturn(revised);
        AgentState state=loop.run(10L,VIDEO,null,REQUEST);
        assertEquals(revised.tasks(),state.plan().tasks());
        assertTrue(state.plan().userRevised());
        verify(model,never()).plan(any(),anyString());
    }
    @Test void memoryQueueSqlFailureCannotLoseFinalCheckpoint() {
        var loop=loop(true,result("两"));
        doThrow(new IllegalStateException("SQL unavailable")).when(jobs).enqueue(any());
        AgentState state=assertDoesNotThrow(() -> loop.run(10L,VIDEO,null,REQUEST));
        verify(checkpoints).saveResult(10L,state,AnalysisMode.GENERAL);
        assertTrue(state.critique().passed());
    }
    @Test void failedFinalCheckpointNeverQueuesMemory() {
        var loop=loop(true,result("两"));
        doThrow(new IllegalStateException("checkpoint SQL")).when(checkpoints).saveResult(anyLong(),any(),any());
        assertThrows(IllegalStateException.class,() -> loop.run(10L,VIDEO,null,REQUEST));
        verifyNoInteractions(jobs);
    }
    @Test void gateSkippedReusesOriginalTerminalCheckpointWithMemoryEnabled() {
        var loop=loop(true,result("两"));
        VideoContext video=new VideoContext(VIDEO.source(),"视频中有几个人",VIDEO.segments());
        AgentState saved=new AgentState(video.userGoal(),new AgentState.AgentPlan(video.userGoal(),List.of("核验人数")),result("两"),
                new AgentState.CriticResult(true,List.of(),List.of(),List.of(),List.of()),1);
        when(checkpoints.loadCriticState(10L,video.userGoal(),AnalysisMode.GENERAL)).thenReturn(saved);
        MemoryRequest request=MemoryRequest.task(1L,10L,video.userGoal(),null,AnalysisMode.GENERAL);
        assertSame(saved,loop.run(10L,video,null,request));
        verifyNoInteractions(model,retrieval,repository,vectors,embeddings);
    }
    private static AgentMemory oldMemory() {
        return new AgentMemory("11111111-1111-1111-1111-111111111111", 1L, Type.PREVIOUS_RESULT, "记忆中有五个人", "old-task", 10L,
                .8, Instant.now(), Instant.now(), Status.ACTIVE, Scope.VIDEO, null, "old-result", "hash");
    }
    @Test void largeProvenanceTreesCannotDisplaceCurrentVideoEvidenceFromPrompt() throws Exception {
        var base=oldMemory();
        var tasks=java.util.stream.IntStream.range(0,64).mapToObj(i->"FULL_TASK_PROVENANCE_"+i+"x".repeat(120)).toList();
        var metadata=new MemoryMetadata("LOSSLESS_COMPRESSION",1,"",false,"","topic",2,List.of(),tasks);
        var memory=new AgentMemory(base.memoryId(),base.userId(),base.type(),base.content(),base.sourceTaskId(),base.sourceVideoId(),
                base.importance(),base.createdAt(),base.updatedAt(),base.status(),base.scope(),base.sessionId(),base.semanticKey(),base.contentHash(),metadata);
        var renderer=new DeepSeekUtils("test","http://127.0.0.1:1","test",1,0,0,0,telemetry,new ObjectMapper().findAndRegisterModules(),mock(ThreadPoolTaskExecutor.class));
        String prompt=renderer.renderContext(VIDEO.withMemory(new MemoryContext(REQUEST.goals(),List.of(memory))));
        assertFalse(prompt.contains("FULL_TASK_PROVENANCE_"));
        assertTrue(prompt.contains("视频中有两个人")); assertTrue(prompt.length()<8000);
    }
}
