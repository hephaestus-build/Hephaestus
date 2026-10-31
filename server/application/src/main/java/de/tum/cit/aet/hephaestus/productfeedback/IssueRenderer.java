package de.tum.cit.aet.hephaestus.productfeedback;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Renders an issue from a validated draft. Every text that came from a person or a model is escaped as plain text, so
 * no link, image, heading, HTML, mention or issue reference in it takes effect on GitHub. The only live link is the one
 * this class writes for a validated related issue.
 */
final class IssueRenderer {
    private IssueRenderer() {}

    /** What the person attached, already free of names and ids. */
    record Context(
            @Nullable String release,
            @Nullable String route,
            @Nullable String browser) {}

    /** One rendered issue. */
    record Rendered(String title, String body) {}

    private static final Pattern MENTION = Pattern.compile("(?<![\\w`])@([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))");
    private static final Pattern REFERENCE = Pattern.compile("(?<![\\w`/])((?:[\\w.-]+/[\\w.-]+)?\\\\#\\d+)");
    private static final Pattern URL = Pattern.compile("(?<!`)\\bhttps?://[^\\s`]+");
    private static final Pattern TITLE_CROSS_REFERENCE = Pattern.compile("([\\w.-]+/[\\w.-]+)#(\\d+)");
    private static final Pattern TITLE_REFERENCE = Pattern.compile("(?<![\\w/])#(\\d+)");

    /** The marker of the public issue a promotion creates, derived so that every attempt and cleanup can find it. */
    static UUID promotionMarker(UUID feedbackId) {
        return UUID.nameUUIDFromBytes((feedbackId + "-public").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param repository {@code owner/name} the issue goes to, for the related-issue link
     * @param privateReason the reason line of a private issue, or null for a public one
     * @param reportId the report's id, written on private issues so that instance admins can find the reporter
     * @param mention the reporter's GitHub login, only when they opted in and the issue is public
     * @param marker the idempotency marker, an HTML comment that GitHub does not show
     */
    static Rendered render(
            Draft draft,
            Context context,
            String repository,
            @Nullable String privateReason,
            @Nullable String reportId,
            @Nullable String mention,
            String marker,
            @Nullable String version) {
        var body = new StringBuilder();
        if (privateReason != null)
            body.append("**Private because:** ").append(privateReason).append("\n\n");
        if (draft.type() == Draft.Type.BUG) {
            section(
                    body,
                    "What happened?",
                    paragraphs(draft.observed(), draft.expected().isBlank() ? "" : "Expected: " + draft.expected()));
            section(body, "How can we reproduce it?", numbered(draft.steps()));
        } else {
            section(body, "User problem", plain(draft.problem()));
            section(body, "Desired outcome", plain(draft.outcome()));
            section(body, "Acceptance criteria", bullets(draft.criteria()));
            section(body, "Additional context", plain(draft.context()));
        }
        section(body, "Version", context.release() == null ? "" : code(context.release()));
        section(body, "Environment", environment(context));
        if (draft.related() != null)
            body.append("Possibly related: https://github.com/")
                    .append(repository)
                    .append("/issues/")
                    .append(draft.related())
                    .append("\n\n");
        body.append("---\n");
        body.append("Drafted by a model from product feedback sent through Hephaestus")
                .append(version == null ? "" : " " + version)
                .append(".");
        if (mention != null) body.append(" Reported by @").append(mention).append('.');
        if (reportId != null) body.append("\nReport ").append(code(reportId)).append(" in the instance admin console.");
        body.append("\n\n<!-- hephaestus-product-feedback:").append(marker).append(" -->\n");
        return new Rendered(title(draft.title()), body.toString());
    }

    /** A title is plain text on GitHub; dropping the {@code @} keeps a name from reading as a mention. */
    static String title(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        String unmentioned = MENTION.matcher(flat).replaceAll("$1");
        String crossed = TITLE_CROSS_REFERENCE.matcher(unmentioned).replaceAll("$1 issue $2");
        return TITLE_REFERENCE.matcher(crossed).replaceAll("issue $1");
    }

    private static void section(StringBuilder body, String heading, String text) {
        if (text.isBlank()) return;
        body.append("### ").append(heading).append("\n\n").append(text).append("\n\n");
    }

    private static String environment(Context context) {
        var lines = new StringBuilder();
        if (context.route() != null)
            lines.append("- Page: ").append(code(context.route())).append('\n');
        if (context.browser() != null)
            lines.append("- Browser: ").append(plain(context.browser())).append('\n');
        return lines.toString().strip();
    }

    private static String paragraphs(String first, String second) {
        String a = plain(first);
        String b = plain(second);
        if (a.isBlank()) return b;
        if (b.isBlank()) return a;
        return a + "\n\n" + b;
    }

    private static String numbered(List<String> items) {
        var out = new StringBuilder();
        for (int i = 0; i < items.size(); i++)
            out.append(i + 1).append(". ").append(plain(items.get(i))).append('\n');
        return out.toString().strip();
    }

    private static String bullets(List<String> items) {
        var out = new StringBuilder();
        for (String item : items) out.append("- ").append(plain(item)).append('\n');
        return out.toString().strip();
    }

    /** A code span that holds any text: the fence is one backtick longer than the longest run inside it. */
    static String code(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);
        String pad = text.startsWith("`") || text.endsWith("`") ? " " : "";
        return fence + pad + text + pad + fence;
    }

    /**
     * Escapes Markdown and HTML so that the text renders as written, then writes mentions, issue references and links
     * as code, where GitHub neither notifies, nor references, nor links.
     */
    static String plain(String text) {
        var escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\', '`', '*', '_', '[', ']', '(', ')', '#', '!', '|', '~', '{', '}' ->
                    escaped.append('\\').append(c);
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> escaped.append(c);
            }
        }
        String result = escaped.toString();
        result = replace(result, URL, match -> code(unescape(match)));
        result = replace(result, MENTION, match -> code(unescape(match)));
        result = replace(result, REFERENCE, match -> code(unescape(match)));
        // A line that starts like a list or a numbered item stays a paragraph line.
        return result.replaceAll("(?m)^(\\s*)([-+]|\\d+\\.)(\\s)", "$1\\\\$2$3");
    }

    private static String unescape(String text) {
        return text.replaceAll("\\\\(.)", "$1")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String replace(String text, Pattern pattern, UnaryOperator<String> wrap) {
        Matcher matcher = pattern.matcher(text);
        var out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(wrap.apply(matcher.group())));
        matcher.appendTail(out);
        return out.toString();
    }
}
