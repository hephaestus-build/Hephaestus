package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The footer every comment Hephaestus posts on reviewed work carries: that it is AI-generated, where the developer
 * answers it in Hephaestus, and, on a summary, why they see it. Hephaestus reads no reaction or reply on the
 * provider, so the footer asks for none. The answer link names the work, not the review, so two reviews of the same
 * work still write the same note and a repeated one is still recognised.
 */
@Component
class PracticeFeedbackCommentFormatter {

    private final String webappUrl;
    private final String preferencesUrl;
    private final WorkspaceSummaryQuery workspaces;
    private final WorkspaceSubdomainProperties subdomains;

    PracticeFeedbackCommentFormatter(
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

    String format(String sanitizedBody, AgentJob job) {
        var sb = new StringBuilder(sanitizedBody.length() + 640);
        sb.append(PullRequestCommentPoster.summaryMarkerFor(job)).append("\n");
        sb.append(sanitizedBody).append("\n\n");
        sb.append("---\n");
        appendMetadataFooter(sb, job);
        appendWhyAndSettingsLink(sb);
        return sb.toString();
    }

    String appendDisclosure(String sanitizedBody, AgentJob job) {
        var sb = new StringBuilder(sanitizedBody.length() + 420);
        sb.append(sanitizedBody).append("\n\n");
        appendMetadataFooter(sb, job);
        appendWhyAndSettingsLink(sb);
        return sb.toString();
    }

    String appendInlineFeedbackPrompt(String sanitizedBody, AgentJob job) {
        var sb = new StringBuilder(sanitizedBody.length() + 240);
        sb.append(sanitizedBody).append("\n\n");
        sb.append("<sub>AI-generated feedback. ").append(respondLink(job)).append("</sub>\n");
        return sb.toString();
    }

    private void appendWhyAndSettingsLink(StringBuilder sb) {
        sb.append("<sub>[Why you see this and how to stop it](")
                .append(preferencesUrl)
                .append(")</sub>\n");
    }

    private void appendMetadataFooter(StringBuilder sb, AgentJob job) {
        sb.append("<sub>Practice review");

        String modelName = snapshotModelName(job.getConfigSnapshot());
        if (modelName != null && !modelName.isBlank()) {
            sb.append(" &middot; ").append(HtmlUtils.htmlEscape(modelName));
        }
        sb.append(". This feedback is AI-generated and can be inaccurate. ")
                .append(respondLink(job))
                .append("</sub>\n");
    }

    /** Where the developer answers what the review of this work said: their own reviews of it, in Hephaestus. */
    private String respondLink(AgentJob job) {
        boolean issue = job.getJobType() == AgentJobType.ISSUE_REVIEW;
        ArtifactKind kind = issue ? ArtifactKinds.ISSUE : ArtifactKinds.PULL_REQUEST;
        JsonNode metadata = Objects.requireNonNull(job.getMetadata(), "A review job carries its metadata");
        long artifactId = metadata.path(issue ? "issue_id" : "pull_request_id").asLong();
        // By id: the job's workspace may be a proxy no session is left to load.
        String slug = workspaces
                .findById(job.getWorkspace().getId())
                .orElseThrow(() -> new IllegalStateException("A review job's workspace exists"))
                .slug();
        String url = UriComponentsBuilder.fromUriString(subdomains.address(slug, webappUrl))
                .pathSegment("feedback", kind.value(), Long.toString(artifactId))
                .build()
                .encode()
                .toUriString();
        return "Answer or dispute it in [Hephaestus](" + url + ").";
    }

    @Nullable
    private static String snapshotModelName(@Nullable JsonNode configSnapshot) {
        if (configSnapshot == null) {
            return null;
        }
        JsonNode model = configSnapshot.path("upstreamModelId");
        return model.isString() ? model.asString() : null;
    }
}
