package com.example.server.memory;

import com.example.server.dto.*;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.example.server.memory.AgentMemory.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs against H2 by default, or an isolated real MySQL with -Dmemory.test.mysql.url=... . */
class MemoryFlowTest {
    AnnotationConfigApplicationContext context;
    MemoryRepository repository;
    MemoryVectorStore vectors;
    EmbeddingUtils embeddings;
    MemoryService memory;
    JdbcTemplate jdbc;

    @Configuration
    @EnableTransactionManagement
    static class Transactions {
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
    }

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("memory.test.mysql.url", System.getenv().getOrDefault("MEMORY_TEST_MYSQL_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        if (url.startsWith("jdbc:mysql:") && !url.matches("jdbc:mysql://[^/]+/dovideo_memory_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Use only the disposable dovideo_memory_test database");
        }
        DriverManagerDataSource source = new DriverManagerDataSource(url,
                System.getProperty("memory.test.mysql.user", System.getenv().getOrDefault("MEMORY_TEST_MYSQL_USER","root")),
                System.getProperty("memory.test.mysql.password", System.getenv().getOrDefault("MEMORY_TEST_MYSQL_PASSWORD","")));
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("agent_memory_sources","agent_memory_jobs","agent_memory_index_outbox","agent_memories","agent_checkpoints","media_files")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE media_files (id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE agent_checkpoints (media_id BIGINT,checkpoint_key VARCHAR(160),stage VARCHAR(64),payload TEXT,updated_at TIMESTAMP(3),PRIMARY KEY(media_id,checkpoint_key))");
        jdbc.update("INSERT INTO users(id) VALUES (1),(2)");
        String schema;
        try (var stream = getClass().getResourceAsStream("/db/migration/V4__create_agent_memories.sql")) {
            schema = new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
        jdbc.execute(schema);
        try (var stream=getClass().getResourceAsStream("/db/migration/V5__memory_reliability_and_compression.sql")) {
            for(String sql:new String(stream.readAllBytes(), StandardCharsets.UTF_8).split(";")) if(!sql.isBlank()) jdbc.execute(sql);
        }
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(MemoryRepository.class, () -> new MemoryRepository(source, 200));
        context.registerBean(MemoryJobRepository.class, () -> new MemoryJobRepository(source,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));
        context.register(Transactions.class);
        context.refresh();
        repository = context.getBean(MemoryRepository.class);
        vectors = mock(MemoryVectorStore.class);
        embeddings = mock(EmbeddingUtils.class);
        when(embeddings.embed(anyString(), anyLong())).thenReturn(List.of(.1, .2));
        memory = new MemoryService(true, 5, 1500, new MemoryGate(), repository, vectors, embeddings, new MemoryExtractor());
    }

    @AfterEach void close() { if (context != null) context.close(); }

    static MemoryRequest request(long user, long video, String query, String session) {
        return new MemoryRequest(user, video, video + ":" + MemoryRepository.hash(query), session, new GoalContext(null, null, query));
    }

    static AgentState success(String query) {
        var result = new AnalysisResult("视频学习笔记", List.of("视频展示了三步操作"),
                List.of(new AnalysisResult.Evidence(100, "ASR", "三步操作", "视频展示了三步操作")), List.of(), List.of());
        return new AgentState(query, new AgentState.AgentPlan(query, List.of("提取三步操作")), result,
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()), 1);
    }

    @Test void recallsSameUsersPreferenceAcrossSessionsAndVideos() {
        var first = request(1, 10, "我喜欢简短的中文要点，请总结视频", "session-a");
        memory.processCompleted(first, success(first.goals().currentGoal()));
        var recalled = memory.prepare(request(1, 11, "按我的习惯总结视频", "session-b"));
        assertTrue(recalled.memories().stream().anyMatch(m -> m.type() == Type.USER_PREFERENCE && m.content().contains("简洁")));
        assertTrue(recalled.memories().stream().allMatch(m -> m.userId() == 1));
        assertNull(recalled.goals().userGoal());
        assertNull(recalled.goals().sessionGoal());
        assertNull(memory.prepare(request(2, 11, "按我的习惯总结视频", "session-b")));
    }

    @Test void continueLastTimeRecallsCompletedEpisodeEvenWithoutVectorMatch() {
        var first = request(1, 10, "总结第一章的视频", "session-a");
        memory.processCompleted(first, success(first.goals().currentGoal()));
        var recalled = memory.prepare(request(1, 11, "按照上次继续", "session-b"));
        assertTrue(recalled.memories().stream().anyMatch(m -> m.type() == Type.EPISODIC_TASK && m.content().contains("总结第一章")));
        assertFalse(recalled.memories().stream().anyMatch(m -> m.type() == Type.PREVIOUS_RESULT));
    }

    @Test void unrelatedQuerySkipsAllMemoryIo() {
        var repo = mock(MemoryRepository.class);
        var gate = spy(new MemoryGate());
        var service = new MemoryService(true, 5, 1500, gate, repo, vectors, embeddings, new MemoryExtractor());
        assertNull(service.prepare(request(1, 10, "视频中有几个人？", "a")));
        verify(gate).evaluate("视频中有几个人？");
        verifyNoInteractions(repo, vectors, embeddings);
    }

    @Test void duplicateMergeAndPreferenceCorrectionSupersedeOldValue() {
        var req = request(1, 10, "query", "a");
        var brief = new Candidate(Type.USER_PREFERENCE, "  用户偏好简洁回答  ", .7, Scope.GLOBAL_USER, "output.length");
        var saved = repository.merge(req, brief);
        var duplicate = repository.merge(req, new Candidate(Type.USER_PREFERENCE, "用户偏好简洁回答", .9, Scope.GLOBAL_USER, "output.length"));
        assertEquals(saved.memoryId(), duplicate.memoryId());
        assertEquals(.9, duplicate.importance());
        assertEquals(saved.createdAt(), duplicate.createdAt());
        assertEquals(1, repository.active(1L, 10L, "a").size());
        repository.merge(req, new Candidate(Type.USER_PREFERENCE, "用户偏好详细回答", .8, Scope.GLOBAL_USER, "output.length"));
        assertEquals(List.of("用户偏好详细回答"), repository.active(1L, 10L, "a").stream().map(AgentMemory::content).toList());
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT status FROM agent_memories WHERE memory_id=?", String.class, saved.memoryId()));
    }

    @Test void disableIsOwnerScopedAndReplayDoesNotResurrectIt() {
        var req = request(1, 10, "query", "a");
        var candidate = new Candidate(Type.IMPORTANT_FACT, "用户正在学视频课程", .8, Scope.GLOBAL_USER, "course");
        var saved = repository.merge(req, candidate);
        assertFalse(memory.disable(2L, saved.memoryId()));
        assertTrue(memory.disable(1L, saved.memoryId()));
        assertNull(repository.merge(req, candidate));
        assertTrue(repository.active(1L, 10L, "a").isEmpty());
    }

    @Test void scopesAreEnforcedAndProtectedKnowledgeIsNotEvicted() {
        var req = request(1, 10, "query", "a");
        repository.merge(req, new Candidate(Type.IMPORTANT_FACT, "会话事实", .8, Scope.SESSION, "session-fact"));
        repository.merge(req, new Candidate(Type.PREVIOUS_RESULT, "视频结果", .8, Scope.VIDEO, "result"));
        assertEquals(2, repository.active(1L, 10L, "a").size());
        assertEquals(1, repository.active(1L, 10L, "b").size());
        assertTrue(repository.active(1L, 11L, "b").isEmpty());
        assertTrue(repository.active(2L, 10L, "a").isEmpty());
        for (int i = 0; i < 30; i++) repository.merge(req,
                new Candidate(Type.IMPORTANT_FACT, "长期事实 " + i, .9, Scope.GLOBAL_USER, "fact:" + i));
        assertEquals(32, repository.activeIds(1L).size());
        assertEquals(32, jdbc.queryForObject("SELECT COUNT(*) FROM agent_memories WHERE user_id=1", Integer.class));
    }

    @Test void vectorHitsCannotBypassOwnerScopeStatusOrContentVersion() {
        var req = request(1, 10, "query", "a");
        var own = repository.merge(req, new Candidate(Type.IMPORTANT_FACT, "自己的事实", .8, Scope.GLOBAL_USER, "own"));
        var other = repository.merge(request(2, 10, "query", "a"), new Candidate(Type.IMPORTANT_FACT, "别人的事实", .9, Scope.GLOBAL_USER, "other"));
        when(vectors.search(any(), anyList(), anyInt())).thenReturn(List.of(
                new MemoryVectorStore.Hit(own.memoryId(), .99, "stale-hash"),
                new MemoryVectorStore.Hit(other.memoryId(), 1, other.contentHash())));
        assertNull(memory.prepare(request(1, 10, "之前保存的事实", "a")));
    }

    @Test void rerankingUsesImportanceAndRecencyBesidesSimilarity() {
        var req = request(1, 10, "query", "a");
        var old = repository.merge(req, new Candidate(Type.IMPORTANT_FACT, "陈旧低价值", .1, Scope.GLOBAL_USER, "old"));
        var recent = repository.merge(req, new Candidate(Type.IMPORTANT_FACT, "近期重要事实", .95, Scope.GLOBAL_USER, "new"));
        jdbc.update("UPDATE agent_memories SET updated_at=? WHERE memory_id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(86400L * 365)), old.memoryId());
        when(vectors.search(any(), anyList(), anyInt())).thenReturn(List.of(
                new MemoryVectorStore.Hit(old.memoryId(), .95, old.contentHash()),
                new MemoryVectorStore.Hit(recent.memoryId(), .75, recent.contentHash())));
        assertEquals(recent.memoryId(), memory.prepare(request(1, 10, "之前保存的事实", "b")).memories().getFirst().memoryId());
    }

    @Test void disabledFeatureMakesNoGateRetrievalExtractionEmbeddingOrStoreCalls() {
        var gate = mock(MemoryGate.class); var repo = mock(MemoryRepository.class); var extractor = mock(MemoryExtractor.class);
        var service = new MemoryService(false, 5, 1500, gate, repo, vectors, embeddings, extractor);
        var req = request(1, 10, "按照上次继续", "a");
        assertNull(service.prepare(req));
        service.processCompleted(req, success(req.goals().currentGoal()));
        service.processFeedback(req, feedback("我喜欢简短回答"));
        assertFalse(service.disable(1L, "id"));
        verifyNoInteractions(gate, repo, vectors, embeddings, extractor);
    }

    @Test void gateEmbeddingQdrantExtractionAndStoreFailuresAreContained() {
        var gate = mock(MemoryGate.class); var repo = mock(MemoryRepository.class); var extractor = mock(MemoryExtractor.class);
        var req = request(1, 10, "按照上次继续", "a");
        var service = new MemoryService(true, 5, 1500, gate, repo, vectors, embeddings, extractor);
        when(gate.evaluate(anyString())).thenThrow(new IllegalStateException("gate"));
        assertNull(service.prepare(req));
        doReturn(new MemoryGate.Decision(true, true)).when(gate).evaluate(anyString());
        when(embeddings.embed(anyString(), anyLong())).thenThrow(new IllegalStateException("embedding"));
        assertDoesNotThrow(() -> service.prepare(req));
        when(extractor.extract(any(), any())).thenThrow(new IllegalStateException("extract"));
        assertThrows(IllegalStateException.class, () -> service.processCompleted(req, success(req.goals().currentGoal())));
        doReturn(List.of(new Candidate(Type.IMPORTANT_FACT, "事实", .8, Scope.GLOBAL_USER, "fact"))).when(extractor).extract(any(), any());
        when(repo.merge(any(), any())).thenThrow(new IllegalStateException("store"));
        assertThrows(IllegalStateException.class, () -> service.processCompleted(req, success(req.goals().currentGoal())));
        verify(repo).merge(any(), any());
    }

    @Test void failedCriticDoesNotCreateEpisodeOrResult() {
        var req = request(1, 10, "我喜欢中文回答", "a");
        var success = success(req.goals().currentGoal());
        memory.processCompleted(req, new AgentState(success.goal(), success.plan(), success.result(),
                new AgentState.CriticResult(false, List.of("无证据"), List.of(), List.of(), List.of()), 2));
        assertTrue(repository.active(1L, 10L, "a").isEmpty());
        verifyNoInteractions(embeddings, vectors);
    }

    @Test void importantFactsAndExplicitFeedbackUseExistingUpdatePath() {
        var req = request(1, 10, "请记住：我正在学习视频分析", "a");
        memory.processCompleted(req, success(req.goals().currentGoal()));
        assertTrue(repository.active(1L, 10L, "a").stream().anyMatch(m -> m.type() == Type.IMPORTANT_FACT));
        memory.processFeedback(req, feedback("我喜欢详细的英文表格"));
        var saved = repository.active(1L, 10L, "a");
        assertTrue(saved.stream().anyMatch(m -> m.type() == Type.USER_FEEDBACK));
        assertTrue(saved.stream().anyMatch(m -> m.type() == Type.USER_PREFERENCE && m.content().contains("英文")));
    }

    @Test void concurrentDuplicateUpdatesRemainOneActiveMemory() throws Exception {
        var req = request(1, 10, "query", "a");
        var candidate = new Candidate(Type.USER_PREFERENCE, "用户偏好中文回答", .8, Scope.GLOBAL_USER, "output.language");
        var ready = new java.util.concurrent.CountDownLatch(4);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<AgentMemory>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return repository.merge(req, candidate);
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (var future : futures) ids.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS).memoryId());
            assertEquals(1, ids.size());
            assertEquals(1, repository.active(1L, 10L, "a").size());
        }
    }

    static AgentFeedback feedback(String comment) {
        return new AgentFeedback(10L, "query", "GENERAL", -1, null, comment, null, List.of(), null, null, Instant.now());
    }
}
