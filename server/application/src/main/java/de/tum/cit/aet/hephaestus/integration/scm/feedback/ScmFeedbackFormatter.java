package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.FencedCodeBlock;
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

    private static final Parser MARKDOWN = Parser.builder().build();

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

    // Hephaestus appends its markers as the last lines of each copy; a marker-shaped comment earlier in the text,
    // such as one quoted in code, is not one of them.
    private static final Pattern MARKER_LINE = Pattern.compile("<!--[ ]*hephaestus[^<>]*-->");

    public static String withdrawnCopyBody(String original) {
        List<String> lines = original.stripTrailing().lines().toList();
        int first = lines.size();
        while (first > 0
                && (lines.get(first - 1).isBlank()
                        || MARKER_LINE.matcher(lines.get(first - 1).strip()).matches())) first--;
        var body = new StringBuilder("This feedback was withdrawn.");
        for (String line : lines.subList(first, lines.size()))
            if (!line.isBlank()) body.append("\n").append(line.strip());
        return body.toString();
    }
}
