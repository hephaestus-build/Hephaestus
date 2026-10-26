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
        return section(new String(
                PiRuntimeFactory.loadClasspathResource("practice-feedback-language.md"), StandardCharsets.UTF_8));
    }

    /** The "Writing feedback" section of the page, to the next heading of its level or the end. */
    static String section(String document) {
        String text = document.replace("\r\n", "\n");
        String heading = "## Writing feedback\n";
        int start = text.indexOf(heading);
        if (start < 0) throw new IllegalStateException("Missing feedback writing guide");
        int end = text.indexOf("\n## ", start + heading.length());
        return text.substring(start, end < 0 ? text.length() : end).strip();
    }
}
