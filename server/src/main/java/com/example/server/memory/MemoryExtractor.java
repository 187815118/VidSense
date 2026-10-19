package com.example.server.memory;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.example.server.memory.AgentMemory.*;

/** Conservative deterministic extraction: no speculative user profile and no raw conversation persistence. */
@Component
public class MemoryExtractor {
    private static final Pattern PREFERENCE = Pattern.compile("^(?:我(?:更)?喜欢|我不喜欢|我不再喜欢|我习惯|我偏好|以后请|今后请)([^。！？!?；;\\n]{1,180})");
    private static final Pattern FACT = Pattern.compile("^请记住[：:，, ]+([^。！？!?\\n]{1,300})");
    private static final Pattern SECRET = Pattern.compile("(?i)密码|口令|密钥|token|password|api.?key|secret");

    public List<Candidate> extract(MemoryRequest request, AgentState state) {
        if (state == null || state.result() == null || state.plan() == null
                || state.critique() == null || !state.critique().passed()) return List.of();
        if (!MemorySafety.safe(request.goals().currentGoal())) return List.of();
        List<Candidate> candidates = new ArrayList<>(explicit(request.goals().currentGoal()));
        String tasks = String.join("；", state.plan().tasks().stream().limit(3).toList());
        candidates.add(new Candidate(Type.EPISODIC_TASK,
                limit("已完成任务：" + request.goals().currentGoal() + "；执行内容：" + tasks + "；产物：" + state.result().title()),
                .65, Scope.GLOBAL_USER, "task:" + request.taskId(),
                taskMetadata(request, state)));
        candidates.add(new Candidate(Type.PREVIOUS_RESULT,
                limit("产物：" + state.result().title() + "；结论摘要：" + String.join("；", state.result().conclusions().stream().limit(3).toList())),
                .60, Scope.VIDEO, "result:" + request.taskId(), taskMetadata(request, state)));
        return candidates.stream().filter(c -> !SECRET.matcher(c.content()).find()).limit(6).toList();
    }

    public List<Candidate> feedback(AgentFeedback feedback) {
        String comment = feedback.comment() == null ? "" : feedback.comment().strip();
        if (!MemorySafety.safe(comment) || !MemorySafety.safe(feedback.correctedGoal() == null ? "" : feedback.correctedGoal())
                || !MemorySafety.safe(String.join("；", feedback.correctedTasks()))) return List.of();
        List<Candidate> candidates = new ArrayList<>(explicit(comment));
        if (!comment.isBlank() || feedback.correctedGoal() != null || !feedback.correctedTasks().isEmpty()
                || feedback.evidenceAccepted() != null || feedback.rating() != null) {
            String content = limit("用户反馈：" + comment + "；评分：" + feedback.rating()
                    + "；修正目标：" + feedback.correctedGoal() + "；修正任务：" + String.join("；", feedback.correctedTasks())
                    + "；证据时间戳：" + feedback.evidenceTimestamp() + "；接受证据：" + feedback.evidenceAccepted());
            candidates.add(new Candidate(Type.USER_FEEDBACK, content, .8, Scope.VIDEO,
                    "feedback:" + MemoryRepository.hash(feedback.goal()),
                    new MemoryMetadata("USER_FEEDBACK", 1, comment.length()<=1000 ? comment : "", MemorySafety.correction(comment), "", "", 0, List.of(), List.of())));
        }
        return candidates.stream().filter(c -> !SECRET.matcher(c.content()).find()).toList();
    }

    private List<Candidate> explicit(String query) {
        if (!MemorySafety.safe(query) || query.matches("(?s).*(视频中|台词|OCR|字幕中|假设|假如|他说|她说|[“”\"‘’]).*")) return List.of();
        List<Candidate> candidates = new ArrayList<>();
        Matcher preference = PREFERENCE.matcher(query.strip());
        if (preference.find()) {
            String text = preference.group(1).strip();
            String quote = preference.group().strip();
            // Preserve negation/conditions literally; never convert "不喜欢详细" to "喜欢详细".
            if (quote.matches("(?s).*(不喜欢|不再|不要).*") || MemorySafety.conditional(quote)) {
                String condition = MemorySafety.conditional(quote) ? quote : "";
                String slot = text.matches(".*(详细|简洁|简短|精简|详尽).*") ? "output.length"
                        : text.matches(".*(中文|英文).*") ? "output.language" : "preference:" + MemoryRepository.hash(quote);
                if (!condition.isEmpty()) slot += ":when:" + MemoryRepository.hash(condition).substring(0,16);
                return List.of(new Candidate(Type.USER_PREFERENCE, quote, .85, Scope.GLOBAL_USER, slot,
                        new MemoryMetadata("USER_REQUEST", 1, quote, MemorySafety.correction(quote), condition, "", 0, List.of(), List.of())));
            }
            // Stable slots make explicit corrections supersede previous values instead of accumulating contradictions.
            if (text.matches(".*(简短|简洁|详细|精简|详尽).*")) {
                String value = text.matches(".*(详细|详尽).*") ? "用户偏好详细回答" : "用户偏好简洁回答";
                candidates.add(new Candidate(Type.USER_PREFERENCE, value, .85, Scope.GLOBAL_USER, "output.length"));
            }
            if (text.matches(".*(中文|英文).*")) {
                candidates.add(new Candidate(Type.USER_PREFERENCE, text.contains("英文") ? "用户偏好英文回答" : "用户偏好中文回答",
                        .85, Scope.GLOBAL_USER, "output.language"));
            }
            if (text.matches(".*(要点|表格|段落).*")) {
                String format = text.contains("表格") ? "表格" : text.contains("要点") ? "要点" : "段落";
                candidates.add(new Candidate(Type.USER_PREFERENCE, "用户偏好以" + format + "组织回答", .8, Scope.GLOBAL_USER, "output.format"));
            }
            // Unknown preferences still require an explicit first-person declaration and stay separate.
            if (candidates.isEmpty()) candidates.add(new Candidate(Type.USER_PREFERENCE, "用户明确偏好：" + text, .75,
                    Scope.GLOBAL_USER, "preference:" + MemoryRepository.hash(text)));
        }
        Matcher fact = FACT.matcher(query.strip());
        if (fact.find() && fact.group(1).startsWith("我")) {
            candidates.add(new Candidate(Type.IMPORTANT_FACT, fact.group(1), .8, Scope.GLOBAL_USER,
                    "fact:" + MemoryRepository.hash(fact.group(1))));
        }
        return candidates.stream().map(c -> new Candidate(c.type(), c.content(), c.importance(), c.scope(), c.semanticKey(),
                new MemoryMetadata("USER_REQUEST", 1, query.strip().split("[。！？!?；;\\n]",2)[0],
                        MemorySafety.correction(query), "", "", 0, List.of(), List.of()))).toList();
    }

    private MemoryMetadata taskMetadata(MemoryRequest request, AgentState state) {
        return new MemoryMetadata("VERIFIED_TASK", 1, "", false, "",
                MemoryRepository.hash(String.join("；", state.plan().tasks())), 0, List.of(), List.of(request.taskId()));
    }

    private String limit(String text) {
        return text.length() <= 1200 ? text : text.substring(0, 1200);
    }
}
