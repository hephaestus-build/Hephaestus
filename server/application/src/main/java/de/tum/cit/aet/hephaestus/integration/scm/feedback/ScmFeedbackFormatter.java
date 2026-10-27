package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

// Key the answer link by work so repeated reviews retain the same copy marker.
@Component
public class ScmFeedbackFormatter {

    private static final Pattern DELIVERY_TAG = Pattern.compile("<!-- hephaestus-diff-note-ck=([A-Za-z0-9_:-]+) -->");

    public static String deliveryTag(String key) {
        return "<!-- hephaestus-diff-note-ck=" + key + " -->";
    }

    public static @Nullable String lastDeliveryKey(String body) {
        Matcher matcher = DELIVERY_TAG.matcher(body);
        String key = null;
        while (matcher.find()) key = matcher.group(1);
        return key;
    }

    private final String webappUrl;
    private final String preferencesUrl;
    private final WorkspaceSummaryQuery workspaces;
    private final WorkspaceSubdomainProperties subdomains;

    public ScmFeedbackFormatter(
            ApplicationProperties applicationProperties,
            WorkspaceSummaryQuery workspaces,
            WorkspaceSubdomainProperties subdomains) {
        this.webappUrl = applicationProperties.webapp().url();
        this.workspaces = workspaces;
        this.subdomains = subdomains;
        this.preferencesUrl = UriComponentsBuilder.fromUriString(webappUrl)
                .pathSegment("settings")
                .fragment("practice-feedback")
                .build()
                .encode()
                .toUriString();
    }

    public String format(String sanitizedBody, ItemRef item, @Nullable String modelName) {
        var sb = new StringBuilder(sanitizedBody.length() + 640);
        sb.append(sanitizedBody).append("\n\n");
        sb.append("---\n");
        appendMetadataFooter(sb, item, modelName);
        appendWhyAndSettingsLink(sb);
        return sb.toString();
    }

    public String appendDisclosure(String sanitizedBody, ItemRef item, @Nullable String modelName) {
        var sb = new StringBuilder(sanitizedBody.length() + 420);
        sb.append(sanitizedBody).append("\n\n");
        appendMetadataFooter(sb, item, modelName);
        appendWhyAndSettingsLink(sb);
        return sb.toString();
    }

    public String appendLineNotePrompt(String sanitizedBody, ItemRef item) {
        var sb = new StringBuilder(sanitizedBody.length() + 240);
        sb.append(sanitizedBody).append("\n\n");
        sb.append("<sub>AI-generated feedback. ").append(respondLink(item)).append("</sub>\n");
        return sb.toString();
    }

    private void appendWhyAndSettingsLink(StringBuilder sb) {
        sb.append("<sub>[Why you see this and how to stop it](")
                .append(preferencesUrl)
                .append(")</sub>\n");
    }

    private void appendMetadataFooter(StringBuilder sb, ItemRef item, @Nullable String modelName) {
        sb.append("<sub>Practice review");

        if (modelName != null && !modelName.isBlank()) {
            sb.append(" &middot; ").append(HtmlUtils.htmlEscape(modelName));
        }
        sb.append(". This feedback is AI-generated and can be inaccurate. ")
                .append(respondLink(item))
                .append("</sub>\n");
    }

    private String respondLink(ItemRef item) {
        String slug = workspaces
                .findById(item.workspaceId())
                .orElseThrow(() -> new IllegalStateException("A review job's workspace exists"))
                .slug();
        String url = UriComponentsBuilder.fromUriString(subdomains.address(slug, webappUrl))
                .pathSegment("feedback", Long.toString(item.itemId()))
                .build()
                .encode()
                .toUriString();
        return "Answer or disagree in [Hephaestus](" + url + ").";
    }

    /** Maximum comment body length before header/footer (GitHub limit is 65,536). */
    static final int MAX_BODY_LENGTH = 60_000;

    // Hidden HTML comments can carry instructions for downstream AI tools.
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");

    private static final Pattern COMMENT_OPENER = Pattern.compile("<!--");

    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    // Stop if sanitization cannot settle within the bound; partial sanitization must not be published.
    private static final int MAX_SANITIZE_PASSES = 16;

    // Only HTTP(S) links may remain live in untrusted feedback.
    private static final Pattern SAFE_LINK_DESTINATION = Pattern.compile("(?i)https?://");

    private static final String TRUNCATION_NOTICE = "\n\n[... truncated. The comment exceeded the length limit.]";

    private static final Pattern HTML_TAG =
            Pattern.compile("</?([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*/?>", Pattern.CASE_INSENSITIVE);

    /**
     * Tags allowed in sanitized output. All attributes are stripped from allowed tags. A disclosure
     * stays: the summary layout puts longer detail in one, and its text is sanitized like the rest.
     */
    static final Set<String> SAFE_HTML_TAGS = Set.of(
            "details",
            "summary",
            "br",
            "hr",
            "code",
            "pre",
            "sub",
            "sup",
            "em",
            "strong",
            "b",
            "i",
            "p",
            "ul",
            "ol",
            "li",
            "blockquote",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "table",
            "thead",
            "tbody",
            "tr",
            "td",
            "th");

    private static final Pattern APPROVAL_LANGUAGE = Pattern.compile(
            "^\\s*(?:LGTM|(?:looks good to me)|(?:approved)|(?:ready to merge)|(?:ship it)|(?:approved by\\b[^\\n]*))[.!?]*\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /**
     * Whether a text carries a standalone approval line. A composed text that does is refused whole
     * before it is published, never shortened: the words around such a line were written with it.
     */
    public static boolean speaksApproval(String body) {
        return APPROVAL_LANGUAGE.matcher(body).find();
    }

    // Strip invisible direction and mention-bypass characters; preserve ZWJ for compound emoji.
    private static final Pattern INVISIBLE_CHARS =
            Pattern.compile("[\\u200B\\u200C\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]");

    // Preserve HTTP(S) autolinks during HTML tag stripping.
    private static final Pattern AUTOLINK = Pattern.compile("<(https?://[^>\\s]+)>");

    public static String sanitize(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String normalized = raw.replace("\r\n", "\n").replace("\r", "\n");
        String settled = settle(INVISIBLE_CHARS.matcher(normalized).replaceAll(""));
        if (settled == null || settled.isBlank()) return "";
        String result = balanceCodeFences(settled);
        // A cut can reopen what a pass settled, so the cut text is settled again; each retry cuts
        // more.
        int budget = MAX_BODY_LENGTH - TRUNCATION_NOTICE.length();
        while (result.length() > MAX_BODY_LENGTH) {
            String cut = settle(truncate(settled, budget));
            if (cut == null) return "";
            result = balanceCodeFences(cut);
            budget = Math.max(0, budget - Math.max(1, result.length() - MAX_BODY_LENGTH));
        }
        return result;
    }

    private static @Nullable String settle(String text) {
        String result = text;
        for (int pass = 0; pass < MAX_SANITIZE_PASSES; pass++) {
            String next = sanitizePass(result);
            if (next == null) return null;
            if (next.equals(result)) return result;
            result = next;
        }
        return null;
    }

    // Protect autolinks before stripping tags; settle HTML before sanitizing Markdown.
    private static @Nullable String sanitizePass(String text) {
        String result = outsideCode(text, HTML_COMMENT, match -> "");
        // An unterminated opener matches nothing above, and runs to end of document in both
        // renderers.
        result = outsideCode(result, COMMENT_OPENER, match -> "");
        result = outsideCode(result, AUTOLINK, match -> match.group(1));
        // Loop until stable: one pass would let <scr<script>ipt> reassemble into <script>.
        String prev;
        do {
            prev = result;
            result = outsideCode(result, HTML_TAG, ScmFeedbackFormatter::safeTag);
        } while (!result.equals(prev));
        result = withoutLiveImagesOrUnsafeLinks(result);
        return result == null ? null : ProviderMentions.escape(result);
    }

    private static String safeTag(MatchResult match) {
        String tagName = match.group(1).toLowerCase(Locale.ROOT);
        if (!SAFE_HTML_TAGS.contains(tagName)) {
            return "";
        }
        String full = match.group();
        if (full.startsWith("</")) return "</" + tagName + ">";
        if (full.endsWith("/>")) return "<" + tagName + " />";
        return "<" + tagName + ">";
    }

    private static String outsideCode(String text, Pattern pattern, Function<MatchResult, String> replacement) {
        StringBuilder out = new StringBuilder(text.length());
        Matcher matcher = pattern.matcher(text);
        int position = 0;
        for (Range code : codeRanges(text)) {
            if (code.from() < position) continue;
            replaceWithin(text, matcher, position, code.from(), replacement, out);
            out.append(text, code.from(), code.to());
            position = code.to();
        }
        replaceWithin(text, matcher, position, text.length(), replacement, out);
        return out.toString();
    }

    private static void replaceWithin(
            String text,
            Matcher matcher,
            int from,
            int to,
            Function<MatchResult, String> replacement,
            StringBuilder out) {
        if (from >= to) return;
        matcher.region(from, to);
        int last = from;
        while (matcher.find()) {
            out.append(text, last, matcher.start()).append(replacement.apply(matcher));
            last = matcher.end();
        }
        out.append(text, last, to);
    }

    private static List<Range> codeRanges(String text) {
        List<Range> ranges = new ArrayList<>();
        MARKDOWN.parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Code code) {
                add(code);
            }

            @Override
            public void visit(FencedCodeBlock block) {
                add(block);
            }

            @Override
            public void visit(IndentedCodeBlock block) {
                add(block);
            }

            private void add(Node node) {
                for (SourceSpan span : node.getSourceSpans()) {
                    ranges.add(new Range(span.getInputIndex(), span.getInputIndex() + span.getLength()));
                }
            }
        });
        ranges.sort(Comparator.comparingInt(Range::from));
        return ranges;
    }

    private record Range(int from, int to) {}

    private static @Nullable String withoutLiveImagesOrUnsafeLinks(String text) {
        List<Edit> edits = new ArrayList<>();
        boolean[] unplaced = {false};
        MARKDOWN.parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                replace(image, "");
            }

            @Override
            public void visit(Link link) {
                if (SAFE_LINK_DESTINATION.matcher(link.getDestination()).lookingAt()) {
                    visitChildren(link);
                    return;
                }
                Range label = rangeOf(link.getFirstChild(), link.getLastChild());
                replace(link, label == null ? "" : text.substring(label.from(), label.to()));
            }

            private void replace(Node node, String replacement) {
                Range range = rangeOf(node, node);
                if (range == null) unplaced[0] = true;
                else edits.add(new Edit(range, replacement));
            }
        });
        if (unplaced[0]) return null;
        StringBuilder out = new StringBuilder(text.length());
        int position = 0;
        edits.sort(Comparator.comparingInt(candidate -> candidate.range().from()));
        for (Edit edit : edits) {
            out.append(text, position, edit.range().from()).append(edit.replacement());
            position = edit.range().to();
        }
        return out.append(text, position, text.length()).toString();
    }

    private static @Nullable Range rangeOf(@Nullable Node first, @Nullable Node last) {
        if (first == null || last == null) return null;
        List<SourceSpan> start = first.getSourceSpans();
        List<SourceSpan> end = last.getSourceSpans();
        if (start.isEmpty() || end.isEmpty()) return null;
        SourceSpan tail = end.getLast();
        return new Range(start.getFirst().getInputIndex(), tail.getInputIndex() + tail.getLength());
    }

    private record Edit(Range range, String replacement) {}

    // Reserve space to close any cut code fence so truncated code cannot become live Markdown.
    private static String truncate(String text, int budget) {
        String cut = text.substring(0, Math.min(budget, text.length()));
        FencedCodeBlock open;
        while ((open = unclosedFence(cut)) != null) {
            String closing = "\n" + closingFence(open);
            if (cut.length() + closing.length() <= budget) {
                cut = cut + closing;
                break;
            }
            cut = cut.substring(0, Math.max(0, budget - closing.length()));
        }
        return cut + TRUNCATION_NOTICE;
    }

    /**
     * Closes a fenced block the body leaves open, so text appended after the body is not swallowed
     * into it. Only a top-level block can run to the end; a block inside a list or quote ends when
     * its container does.
     */
    public static String balanceCodeFences(String text) {
        FencedCodeBlock open = unclosedFence(text);
        return open == null ? text : text + "\n" + closingFence(open);
    }

    private static @Nullable FencedCodeBlock unclosedFence(String text) {
        return MARKDOWN.parse(text).getLastChild() instanceof FencedCodeBlock block
                        && block.getClosingFenceLength() == null
                ? block
                : null;
    }

    private static String closingFence(FencedCodeBlock block) {
        Integer opening = block.getOpeningFenceLength();
        return block.getFenceCharacter().repeat(opening == null ? 3 : opening);
    }

    public static String withdrawnCopyBody(String original) {
        var markers = Pattern.compile("<!--[ ]*hephaestus[^<>]*-->").matcher(original);
        var body = new StringBuilder("This feedback was withdrawn.");
        while (markers.find()) body.append("\n").append(markers.group());
        return body.toString();
    }
}
