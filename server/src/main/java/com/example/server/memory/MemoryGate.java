package com.example.server.memory;

import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.regex.Pattern;

/** A cheap history-dependency test; no model, database or embedding call. */
@Component
public class MemoryGate {
    private static final Pattern HISTORY = Pattern.compile(
            "之前|上次|以前|继续|接着|我的习惯|我的偏好|我喜欢的|按.*习惯|我完成过|我做过|remember|previous|last time|continue|my preferences|my habits");
    private static final Pattern EPISODE = Pattern.compile("之前|上次|以前|继续|接着|完成过|做过|previous|last time|continue");

    public Decision evaluate(String query) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return new Decision(HISTORY.matcher(text).find(), EPISODE.matcher(text).find());
    }

    public record Decision(boolean retrieve, boolean episodic) { }
}
