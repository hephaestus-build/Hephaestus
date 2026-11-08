package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubPrNodeIdResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackCopyReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubFeedbackCopyReader implements FeedbackCopyReader {
    private final GitHubGraphQlClientProvider provider;
    private final GitHubPrNodeIdResolver subjects;
    private final ScmFeedbackTargetResolver targets;
    private final ObjectMapper mapper;

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public List<CopyReadback> read(FeedbackTarget target, List<CopyReadRequest> copies) {
        if (copies.isEmpty()) return List.of();
        if (copies.size() > 31) throw new IllegalArgumentException("A package has at most 31 copies");
        long workspace = target.item().workspaceId();
        try {
            if (provider.isRateLimitCritical(workspace)) return unavailable(copies);
            String subject = subject(target);
            var ids = copies.stream()
                    .map(GitHubFeedbackCopyReader::nodeId)
                    .distinct()
                    .toList();
            var response = provider.forScope(workspace)
                    .documentName("GetOwnedFeedbackCopies")
                    .variable("ids", ids)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
            if (response == null || !response.getErrors().isEmpty()) return unavailable(copies);
            provider.trackRateLimit(workspace, response);
            String viewer = response.field("viewer.login").getValue();
            Object payload = response.field("nodes").getValue();
            JsonNode nodes = mapper.valueToTree(payload);
            if (viewer == null || viewer.isBlank() || !nodes.isArray() || nodes.size() != ids.size())
                return unavailable(copies);
            var indexed = new HashMap<String, JsonNode>();
            for (int index = 0; index < ids.size(); index++) {
                var node = Objects.requireNonNull(nodes.get(index));
                if (!node.isNull() && !ids.get(index).equals(text(node, "id"))) return unavailable(copies);
                indexed.put(ids.get(index), node);
            }
            return copies.stream()
                    .map(copy -> snapshot(copy, indexed.get(nodeId(copy)), subject, viewer))
                    .toList();
        } catch (RuntimeException unavailable) {
            return unavailable(copies);
        }
    }

    private String subject(FeedbackTarget target) {
        String subject = targets.subject(target, kind());
        long workspace = target.item().workspaceId();
        if (GitHubSummaryWriter.isIssueSubject(subject)) {
            var issue = GitHubSummaryWriter.parseIssueSubjectExternalId(subject);
            return subjects.resolveIssue(workspace, issue.owner(), issue.name(), issue.number());
        }
        var pull = GitHubSummaryWriter.parseSubjectExternalId(subject);
        return subjects.resolve(workspace, pull.owner(), pull.name(), pull.number());
    }

    private static String nodeId(CopyReadRequest copy) {
        return copy.threadRef() == null ? copy.externalRef() : copy.threadRef();
    }

    private static List<CopyReadback> unavailable(List<CopyReadRequest> copies) {
        return copies.stream()
                .map(copy -> CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE))
                .toList();
    }

    private static CopyReadback snapshot(CopyReadRequest copy, @Nullable JsonNode node, String subject, String viewer) {
        if (node == null) return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        if (node.isNull()) return CopyReadback.refused(copy.copyId(), CopyReadback.Status.GONE);
        String type = text(node, "__typename");
        var content = node;
        FeedbackAnchor anchor = null;
        boolean resolved = false;
        boolean outdated = false;
        if ("PullRequestReviewThread".equals(type)) {
            if (copy.threadRef() == null || !subject.equals(text(node.path("pullRequest"), "id")))
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
            var first = node.path("comments").path("nodes").get(0);
            if (first == null || !copy.externalRef().equals(text(first, "id")))
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
            if (!node.path("comments").path("totalCount").isIntegralNumber())
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
            if (node.path("comments").path("totalCount").asInt() != 1)
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.HUMAN_EDITED);
            content = first;
            if (!node.path("isResolved").isBoolean() || !node.path("isOutdated").isBoolean())
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
            resolved = node.path("isResolved").asBoolean();
            outdated = node.path("isOutdated").asBoolean();
            anchor = anchor(node, outdated);
        } else if ("PullRequestReview".equals(type)) {
            if (!subject.equals(text(node.path("pullRequest"), "id")))
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
            if (!"COMMENTED".equals(text(node, "state")))
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        } else if ("IssueComment".equals(type)) {
            if (!subject.equals(text(node.path("commentPullRequest"), "id"))
                    && !subject.equals(text(node.path("issue"), "id")))
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
        } else return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
        if (!content.path("viewerDidAuthor").isBoolean())
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        if (!content.path("viewerDidAuthor").asBoolean())
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
        var editor = content.path("editor");
        if (editor.isMissingNode() || (!editor.isNull() && text(editor, "login") == null))
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        if (!editor.isNull() && !viewer.equals(text(editor, "login")))
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.HUMAN_EDITED);
        String body = text(content, "body");
        String rendered = text(content, "bodyHTML");
        if (body == null || rendered == null || body.length() > 128_000 || rendered.length() > 128_000)
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        if (!ScmFeedbackFormatter.carries(body, copy.writeMarker()))
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
        return new CopyReadback(copy.copyId(), CopyReadback.Status.FOUND, body, rendered, anchor, resolved, outdated);
    }

    private static FeedbackAnchor anchor(JsonNode thread, boolean outdated) {
        String path = text(thread, "path");
        if (path == null) throw new IllegalArgumentException("The provider omitted the note's path");
        if ("FILE".equals(text(thread, "subjectType"))) return new FeedbackAnchor(path, null, null, null);
        Integer end = line(thread, outdated ? "originalLine" : "line");
        Integer start = line(thread, outdated ? "originalStartLine" : "startLine");
        if (end == null) throw new IllegalArgumentException("The provider omitted the note's line");
        CitationSide side = switch (String.valueOf(text(thread, "diffSide"))) {
            case "LEFT" -> CitationSide.OLD;
            case "RIGHT" -> CitationSide.NEW;
            default -> throw new IllegalArgumentException("The provider omitted the note's side");
        };
        if (start != null && !Objects.equals(text(thread, "diffSide"), text(thread, "startDiffSide")))
            throw new IllegalArgumentException("The provider returned a mixed-side range");
        return new FeedbackAnchor(path, start == null ? end : start, end, side);
    }

    private static @Nullable Integer line(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isNull() || value.isMissingNode()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() <= 0)
            throw new IllegalArgumentException("The provider returned an invalid line");
        return value.asInt();
    }

    private static @Nullable String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
