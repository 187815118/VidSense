package com.example.server.memory;

import java.util.Locale;
import java.util.regex.Pattern;
import static com.example.server.memory.AgentMemory.*;

/** Reject credentials before model calls, durable queue writes and memory writes. */
public final class MemorySafety {
    private MemorySafety() { }
    private static final Pattern SECRET = Pattern.compile(
            "(?i)密码|口令|密钥|凭据|凭证|授权码|token|password|api[ _-]?key|access[ _-]?key|credential|secret|bearer\\s+|sk-[a-zA-Z0-9]{8,}|-----BEGIN.*PRIVATE KEY");
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)忽略.{0,12}(规则|指令|提示)|泄露|系统提示词|ignore.{0,30}(instruction|rule)|system\\s*prompt|<\\|.*?\\|>");
    public static boolean safe(String text) {
        return text != null && !SECRET.matcher(text).find() && !INJECTION.matcher(text).find();
    }
    public static String normalized(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    public static boolean correction(String quote) {
        return quote != null && quote.matches("(?s).*(不喜欢|不再|不要|改为|改成|纠正|更正|以后请|今后请|instead|no longer|do not|don't).*" );
    }
    public static boolean explicit(String quote) {
        return quote != null && quote.matches("(?is).*(我喜欢|我更喜欢|我不喜欢|我不再|我习惯|我偏好|我的偏好|以后|今后|回答.{0,8}(请|不要)|请.{0,8}回答|I prefer|I like|I dislike|don't like|please.*answer).*" );
    }
    public static boolean conditional(String text) {
        return text != null && text.matches("(?s).*(如果|当|仅在|只有|暂时|这次|时|对于|针对|遇到|过去|以前|曾经).*" );
    }
    /** Repair a model-truncated declaration by widening to the exact source sentence, never inventing a speaker. */
    public static String expandDeclarationQuote(String text,String quote) {
        if(quote.length()<2 || explicit(quote)) return quote;
        int at=text.indexOf(quote);
        if(at<0 || text.indexOf(quote,at+quote.length())>=0) return quote;
        int start=0,end=text.length();
        for(char b:new char[]{'。','！','？','!','?','；',';','\n'}) {
            start=Math.max(start,text.lastIndexOf(b,at)+1);
            int next=text.indexOf(b,at+quote.length()); if(next>=0) end=Math.min(end,next);
        }
        String sentence=text.substring(start,end).strip();
        return explicit(sentence) && trustedDeclaration(text,sentence) ? sentence : quote;
    }
    public static boolean trustedDeclaration(String text,String quote) {
        int at=text.indexOf(quote);
        if(at<0) return false;
        int start=0,end=text.length();
        for(char boundary:new char[]{'。','！','？','!','?','；',';','\n'}) {
            start=Math.max(start,text.lastIndexOf(boundary,at)+1);
            int next=text.indexOf(boundary,at+quote.length());
            if(next>=0) end=Math.min(end,next);
        }
        String sentence=text.substring(start,end);
        if(sentence.matches("(?is).*(视频中|台词|OCR|字幕中|假设|假如|角色扮演|他说|她说|原文|[“”\"‘’]).*")) return false;
        for(String marker:ListHolder.CONSTRAINTS) if(sentence.contains(marker) && !quote.contains(marker)) return false;
        return true;
    }
    private static final class ListHolder {
        static final String[] CONSTRAINTS={"不喜欢","不再","不要","如果","当","时","过去","以前","曾经","仅在","只有","暂时","这次","对于","针对"};
    }
    public static void validate(MemoryRequest request, Candidate c) {
        if (request == null || request.userId() == null || c == null || c.type() == null || c.scope() == null
                || c.content() == null || c.content().isBlank() || c.content().length() > 1200 || !safe(c.content())
                || c.semanticKey() == null || !c.semanticKey().matches("[A-Za-z0-9_.:/-]{1,160}")
                || !Double.isFinite(c.importance()) || c.importance() < 0 || c.importance() > 1
                || c.metadata() == null || !Double.isFinite(c.metadata().confidence())
                || c.metadata().confidence() < .75 || c.metadata().confidence() > 1
                || c.metadata().level() < 0 || c.metadata().level() > 8
                || !safe(c.metadata().quote()) || !safe(c.metadata().condition())
                || c.metadata().condition().length() > 300 || c.metadata().quote().length() > 1000
                || (c.type() == Type.PREVIOUS_RESULT && c.scope() != Scope.VIDEO)
                || (c.scope() == Scope.VIDEO && request.videoId() == null)
                || (c.scope() == Scope.SESSION && request.sessionId() == null)) {
            throw new IllegalArgumentException("Invalid or unsafe memory candidate");
        }
    }
}
