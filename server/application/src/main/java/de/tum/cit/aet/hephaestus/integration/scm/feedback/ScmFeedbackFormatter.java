package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
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

    /** Resolve links once, before a copy fence or provider request. */
    public Footer footer(ItemRef item) {
        String respond = respondLink(item);
        String settings = "<sub>[Why you see this and how to stop it](" + preferencesUrl + ")</sub>\n";
        return new Footer(
                item.workspaceId(),
                "<sub>Practice review. This feedback is AI-generated and can be inaccurate. " + respond + "</sub>\n"
                        + settings,
                "<sub>AI-generated feedback. " + respond + "</sub>\n" + settings);
    }

    /** Immutable rendering for one target; it does not read settings or workspace data again. */
    public record Footer(long workspaceId, String summary, String lineNote) {
        public String summary(SummaryContent content) {
            String body = PostedText.summary(content);
            String marker = Objects.requireNonNullElse(content.writeMarker(), "");
            if (marker.isBlank()) return appendDisclosure(body).stripTrailing();
            body = body.substring(0, body.length() - marker.length()).stripTrailing();
            return appendDisclosure(body) + "\n" + marker;
        }

        private String appendDisclosure(String sanitizedBody) {
            return sanitizedBody + "\n\n" + summary;
        }

        public String appendLineNotePrompt(String sanitizedBody) {
            return sanitizedBody + "\n\n" + lineNote;
        }
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
