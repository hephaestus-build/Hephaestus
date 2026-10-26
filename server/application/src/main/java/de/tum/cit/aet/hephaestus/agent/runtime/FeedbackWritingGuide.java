package de.tum.cit.aet.hephaestus.agent.runtime;

import java.nio.charset.StandardCharsets;

/**
 * The product voice shared by the feedback composers and Heph. The build copies its owning docs page into the jar, so
 * the prompts read the same section that contributors edit.
 */
public final class FeedbackWritingGuide {
    private static final String TEXT = load();

    private FeedbackWritingGuide() {}

    public static String text() {
        return TEXT;
    }

    private static String load() {
        String document = new String(
                        PiRuntimeFactory.loadClasspathResource("practice-feedback-language.md"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String heading = "## Writing feedback\n";
        int start = document.indexOf(heading);
        if (start < 0) throw new IllegalStateException("Missing feedback writing guide");
        int end = document.indexOf("\n## ", start + heading.length());
        return document.substring(start, end < 0 ? document.length() : end).strip();
    }
}
