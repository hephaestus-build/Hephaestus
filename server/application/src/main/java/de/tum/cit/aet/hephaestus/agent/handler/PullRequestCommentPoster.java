package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliverySuppressedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.UpdateOutcome;
import java.io.Serial;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Sanitizes agent output and dispatches formatted feedback through provider-specific channels. */
class PullRequestCommentPoster {

    private static final Logger log = LoggerFactory.getLogger(PullRequestCommentPoster.class);

    static final Duration GRAPHQL_TIMEOUT = Duration.ofSeconds(15);

    /** Maximum comment body length before header/footer (GitHub limit is 65,536). */
    static final int MAX_BODY_LENGTH = 60_000;

    /** Shared by posting and deduplication; these paths must never construct the marker independently. */
    static final String SUMMARY_MARKER_PREFIX = "<!-- hephaestus:practice-review:";

    /** Matches @mentions (e.g., @username) — backtick-escaped to prevent notification spam.
     *  Lookbehind covers start-of-line, whitespace, punctuation, and markdown formatting chars
     *  ({@code * _ ~ > | - `}) to prevent bypass via {@code *@user*}, {@code >@user}, {@code - @user}, or a
     *  literal backtick outside code. */
    private static final Pattern AT_MENTION =
            Pattern.compile("(?<=^|[\\s(\\[\"'*_~>|#!+={}\\-,.:;/)`])@([a-zA-Z0-9][-a-zA-Z0-9._]*)", Pattern.MULTILINE);

    /** Matches HTML comments — stripped to prevent hidden instructions for AI tools. */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");

    private static final Pattern COMMENT_OPENER = Pattern.compile("<!--");

    /** Parses with source positions, so code literals can be told apart from live Markdown and HTML. */
    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    /**
     * Bounds the reparse loop. Removals only shorten the text and a wrapped mention is code on the next pass, so
     * ordinary text settles in two passes; a text that has not settled by the bound is not published.
     */
    private static final int MAX_SANITIZE_PASSES = 16;

    /** Only http(s) links stay live; any other destination could phish or run script from untrusted output. */
    private static final Pattern SAFE_LINK_DESTINATION = Pattern.compile("(?i)https?://");

    private static final String TRUNCATION_NOTICE = "\n\n[... truncated. The comment exceeded the length limit.]";

    private static final Pattern HTML_TAG =
            Pattern.compile("</?([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*/?>", Pattern.CASE_INSENSITIVE);

    /**
     * Tags allowed in sanitized output. All attributes are stripped from allowed tags.
     * Notably excludes disclosure tags so agent content cannot create misleading hidden sections.
     */
    static final Set<String> SAFE_HTML_TAGS = Set.of(
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

    /**
     * Matches standalone approval language that could mislead reviewers.
     * Tolerates trailing punctuation (e.g., "LGTM!", "Approved.").
     */
    private static final Pattern APPROVAL_LANGUAGE = Pattern.compile(
            "^\\s*(?:LGTM|(?:looks good to me)|(?:approved)|(?:ready to merge)|(?:ship it)|(?:approved by\\b[^\\n]*))[.!?]*\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /**
     * Whether a text carries a standalone approval line. A composed text that does is refused whole before it is
     * published, never shortened: the words around such a line were written with it.
     */
    static boolean speaksApproval(String body) {
        return APPROVAL_LANGUAGE.matcher(body).find();
    }

    /**
     * Matches invisible Unicode characters: bidi controls, zero-width chars, BOM.
     * Prevents text direction attacks and @mention bypass via zero-width spaces.
     * Excludes U+200D (Zero Width Joiner) — used in compound emoji sequences.
     */
    private static final Pattern INVISIBLE_CHARS =
            Pattern.compile("[\\u200B\\u200C\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]");

    /** Matches markdown autolinks: &lt;https://...&gt; — protected from HTML tag stripping. */
    private static final Pattern AUTOLINK = Pattern.compile("<(https?://[^>\\s]+)>");

    private final Map<IntegrationKind, SummaryChannel> channels;

    PullRequestCommentPoster(List<SummaryChannel> feedbackChannels) {
        EnumMap<IntegrationKind, SummaryChannel> map = new EnumMap<>(IntegrationKind.class);
        for (SummaryChannel channel : feedbackChannels) {
            SummaryChannel previous = map.putIfAbsent(channel.kind(), channel);
            if (previous != null) {
                throw new IllegalStateException("Duplicate SummaryChannel for kind " + channel.kind()
                        + ": "
                        + previous.getClass().getName()
                        + " conflicts with "
                        + channel.getClass().getName());
            }
        }
        this.channels = map;
    }

    /**
     * Resolves where a summary would land without calling the provider, so a job that cannot name its target
     * fails before its dispatch records a write, and the marker lookup reads the thread the write would post to.
     */
    SummaryWrite summaryWrite(AgentJob job, boolean issue, String formattedBody, String marker) {
        long workspaceId = job.getWorkspace().getId();
        IntegrationKind kind = requireIntegrationKind(job);
        SummaryChannel channel = requireChannel(kind);
        FeedbackTarget target = issue ? buildIssueTarget(job, kind, workspaceId) : buildTarget(job, kind, workspaceId);
        return new SummaryWrite(job, channel, target, new FeedbackContent(formattedBody, marker));
    }

    /** Returns {@code UNKNOWN}, never {@code ABSENT}, when the lookup cannot be completed. */
    ExistingDeliveryLookup findExisting(SummaryWrite write) {
        try {
            return lookup(write.channel(), write.target(), write.content());
        } catch (RuntimeException e) {
            log.debug(
                    "Existing-summary dedup lookup failed (treated as unknown): jobId={}, error={}",
                    write.job().getId(),
                    e.toString());
            return ExistingDeliveryLookup.unknown();
        }
    }

    SummaryHandle post(SummaryWrite write) {
        try {
            SummaryHandle handle = write.channel().postSummary(write.target(), write.content());
            log.info(
                    "Posted feedback comment: jobId={}, kind={}, subject={}, commentId={}",
                    write.job().getId(),
                    write.channel().kind(),
                    write.target().subjectExternalId(),
                    handle.externalId());
            return handle;
        } catch (OutboundEgressSuppressedException e) {
            throw new JobDeliverySuppressedException(e.toString(), e);
        } catch (FeedbackNotSentException e) {
            throw new SummaryNotSentException(e.toString(), e);
        } catch (FeedbackDeliveryException e) {
            throw new JobDeliveryException(e.toString(), e);
        }
    }

    /** The channel proved its create request was never sent, so the dispatch may release its write fence. */
    static final class SummaryNotSentException extends JobDeliveryException {
        @Serial
        private static final long serialVersionUID = 1L;

        SummaryNotSentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record SummaryWrite(AgentJob job, SummaryChannel channel, FeedbackTarget target, FeedbackContent content) {}

    /**
     * Rewrites a summary Hephaestus already posted, addressed by the id its post returned. A channel that cannot
     * edit, or a job that no longer names one, answers {@code UNSUPPORTED}; a brake or transport failure answers
     * {@code TRANSIENT}, so the caller tries again rather than reporting the comment as changed.
     */
    UpdateOutcome editSummary(
            AgentJob job, String externalId, String formattedBody, @Nullable UUID approvedFeedbackId) {
        try {
            JsonNode metadata = job.getMetadata();
            String marker =
                    approvedFeedbackId == null ? summaryMarkerFor(job) : approvedFeedbackMarker(approvedFeedbackId);
            SummaryWrite write =
                    summaryWrite(job, metadata != null && metadata.has("issue_number"), formattedBody, marker);
            return write.channel().updateSummary(write.target(), externalId, write.content());
        } catch (JobDeliveryException e) {
            return UpdateOutcome.unsupported();
        } catch (FeedbackDeliveryException e) {
            return UpdateOutcome.transientFailure(e.toString());
        }
    }

    private static ExistingDeliveryLookup lookup(
            SummaryChannel channel, FeedbackTarget target, FeedbackContent expected) {
        SummaryChannel.ExistingSummaryLookup lookup = channel.findExistingSummary(target, expected);
        return switch (lookup.kind()) {
            case FOUND ->
                ExistingDeliveryLookup.found(
                        Objects.requireNonNull(lookup.handle()).externalId(),
                        lookup.handle().url());
            case ABSENT -> ExistingDeliveryLookup.absent();
            case UNKNOWN -> ExistingDeliveryLookup.unknown();
        };
    }

    private SummaryChannel requireChannel(IntegrationKind kind) {
        SummaryChannel channel = channels.get(kind);
        if (channel == null) {
            throw new JobDeliveryException("No SummaryChannel is wired for kind " + kind
                    + ". Check that the vendor integration is enabled and its channel bean is registered.");
        }
        return channel;
    }

    private IntegrationKind requireIntegrationKind(AgentJob job) {
        IntegrationKind kind = job.getIntegrationKind();
        if (kind == null) {
            throw new JobDeliveryException(
                    "AgentJob.integrationKind is null, so the server cannot resolve a delivery channel. jobId="
                            + job.getId());
        }
        return kind;
    }

    private FeedbackTarget buildIssueTarget(AgentJob job, IntegrationKind kind, long workspaceId) {
        JsonNode metadata = job.getMetadata();
        String repoFullName = requireMetadataText(metadata, "repository_full_name");
        int issueNumber = requireMetadataInt(metadata, "issue_number");
        String subjectExternalId;
        try {
            subjectExternalId = requireChannel(kind).formatIssueSubjectId(repoFullName, issueNumber);
        } catch (IllegalArgumentException e) {
            throw new JobDeliveryException(e.toString(), e);
        }
        return new FeedbackTarget(new IntegrationRef(kind, workspaceId, null), subjectExternalId, null);
    }

    FeedbackTarget buildTarget(AgentJob job, IntegrationKind kind, long workspaceId) {
        JsonNode metadata = job.getMetadata();
        String repoFullName = requireMetadataText(metadata, "repository_full_name");
        int prNumber = requireMetadataInt(metadata, "pr_number");

        SummaryChannel channel = requireChannel(kind);
        String subjectExternalId;
        try {
            subjectExternalId = channel.formatPullRequestSubjectId(repoFullName, prNumber);
        } catch (IllegalArgumentException e) {
            throw new JobDeliveryException(e.toString(), e);
        }

        String reviewedRevision = optionalMetadataText(metadata, "commit_sha");

        IntegrationRef ref = new IntegrationRef(kind, workspaceId, null);
        return new FeedbackTarget(ref, subjectExternalId, reviewedRevision);
    }

    static String summaryMarkerFor(AgentJob job) {
        return SUMMARY_MARKER_PREFIX + job.getId() + " -->";
    }

    static String approvedFeedbackMarker(UUID feedbackId) {
        return "<!-- hephaestus:approved-feedback:" + feedbackId + " -->";
    }

    /**
     * Sanitizes untrusted agent output for safe inclusion in git provider comments.
     *
     * <p>Code the CommonMark parser finds — code spans, fenced and indented code blocks — passes the transforms
     * unchanged, so {@code Binding<Bool>} or {@code @State} in code stays code. Line endings and invisible controls
     * are normalized everywhere. A live image or unsafe link is handled whole, whatever code its label holds. Each
     * pass parses again, so a construct that a removal or a cut makes live is sanitized rather than trusted as code;
     * a text that does not settle is not published.
     */
    static String sanitize(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String normalized = raw.replace("\r\n", "\n").replace("\r", "\n");
        String settled = settle(INVISIBLE_CHARS.matcher(normalized).replaceAll(""));
        if (settled == null || settled.isBlank()) return "";
        String result = balanceCodeFences(settled);
        // A cut can reopen what a pass settled, so the cut text is settled again; each retry cuts more.
        int budget = MAX_BODY_LENGTH - TRUNCATION_NOTICE.length();
        while (result.length() > MAX_BODY_LENGTH) {
            String cut = settle(truncate(settled, budget));
            if (cut == null) return "";
            result = balanceCodeFences(cut);
            budget = Math.max(0, budget - Math.max(1, result.length() - MAX_BODY_LENGTH));
        }
        return result;
    }

    /** The text once a pass changes nothing more, or null when it does not settle within the bound. */
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

    /** One pass in the load-bearing order: autolinks survive tag stripping, which settles before Markdown. */
    private static @Nullable String sanitizePass(String text) {
        String result = outsideCode(text, HTML_COMMENT, match -> "");
        // An unterminated opener matches nothing above, and runs to end of document in both renderers.
        result = outsideCode(result, COMMENT_OPENER, match -> "");
        result = outsideCode(result, AUTOLINK, match -> match.group(1));
        // Loop until stable: one pass would let <scr<script>ipt> reassemble into <script>.
        String prev;
        do {
            prev = result;
            result = outsideCode(result, HTML_TAG, PullRequestCommentPoster::safeTag);
        } while (!result.equals(prev));
        result = withoutLiveImagesOrUnsafeLinks(result);
        return result == null ? null : outsideCode(result, AT_MENTION, match -> "`@" + match.group(1) + "`");
    }

    /** Keeps a safe tag without its attributes, so no onclick/onload survives, and drops every other tag. */
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

    /**
     * Applies {@code replacement} to each match of {@code pattern} in the text between code literals. Each stretch
     * is matched on its own, so a mention right after a code span counts as starting a word.
     */
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

    /** The source ranges of every code span and code block line, in order. */
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

    /**
     * Removes every live image and reduces every link that is not http(s) to its label, both as the parser reads
     * them: the whole construct goes, whatever its label holds, and the label keeps its own source text. An image or
     * link inside a label that stays is handled by the next pass. Null when the parser gives such a construct no
     * source position, so it cannot be removed.
     */
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

    /** From the start of {@code first} to the end of {@code last}; null when either has no source position. */
    private static @Nullable Range rangeOf(@Nullable Node first, @Nullable Node last) {
        if (first == null || last == null) return null;
        List<SourceSpan> start = first.getSourceSpans();
        List<SourceSpan> end = last.getSourceSpans();
        if (start.isEmpty() || end.isEmpty()) return null;
        SourceSpan tail = end.getLast();
        return new Range(start.getFirst().getInputIndex(), tail.getInputIndex() + tail.getLength());
    }

    private record Edit(Range range, String replacement) {}

    /**
     * The first {@code budget} characters, with a fence the cut leaves open closed inside the budget, then the
     * notice. The cut moves back until the closing fence fits, so the code stays code.
     */
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
     * Closes a fenced block the body leaves open, so text appended after the body is not swallowed into it. Only a
     * top-level block can run to the end; a block inside a list or quote ends when its container does.
     */
    static String balanceCodeFences(String text) {
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

    static String requireMetadataText(@Nullable JsonNode metadata, String field) {
        if (metadata == null) {
            throw new JobDeliveryException("Missing required metadata field: " + field);
        }
        JsonNode node = metadata.get(field);
        if (node == null || node.isNull()) {
            throw new JobDeliveryException("Missing required metadata field: " + field);
        }
        return node.asString();
    }

    @Nullable
    static String optionalMetadataText(@Nullable JsonNode metadata, String field) {
        if (metadata == null) {
            return null;
        }
        JsonNode node = metadata.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asString();
    }

    static int requireMetadataInt(@Nullable JsonNode metadata, String field) {
        if (metadata == null) {
            throw new JobDeliveryException("Missing required metadata field: " + field);
        }
        JsonNode node = metadata.get(field);
        if (node == null || node.isNull()) {
            throw new JobDeliveryException("Missing required metadata field: " + field);
        }
        if (!node.isNumber()) {
            throw new JobDeliveryException(
                    "Expected numeric metadata field '" + field + "', got: " + node.getNodeType());
        }
        return node.asInt();
    }
}
