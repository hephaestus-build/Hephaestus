package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.mockito.Answers;

public final class ScmFeedbackFixtures {
    private static final AtomicLong NEXT = new AtomicLong(100);
    private static final Map<ItemRef, String> SUBJECTS = new ConcurrentHashMap<>();

    private ScmFeedbackFixtures() {}

    public static FeedbackTarget target(IntegrationRef integration, String subject, @Nullable String head) {
        return target(integration, subject, "a".repeat(40), head);
    }

    public static FeedbackTarget target(
            IntegrationRef integration, String subject, String base, @Nullable String head) {
        long id = NEXT.incrementAndGet();
        boolean issue =
                subject.contains("/issues/") || (integration.kind().name().equals("GITLAB") && subject.contains("#"));
        ItemRef item = new ItemRef(
                integration.workspaceId(), id, new ItemKind(issue ? "scm.issue" : "scm.pull_request"), id, null, null);
        SUBJECTS.put(item, subject);
        ItemVersion version = head == null
                ? new ItemVersion("test-unpinned", null)
                : ScmVersion.pullRequest(base, head, "Test", null);
        return new FeedbackTarget(item, integration, version);
    }

    public static ScmFeedbackTargetResolver resolver() {
        return mock(ScmFeedbackTargetResolver.class, call -> {
            if (call.getMethod().getName().equals("serverUrl")) return "https://github.com";
            if (call.getMethod().getName().equals("subject")) {
                FeedbackTarget target = call.getArgument(0);
                String subject = SUBJECTS.get(target.item());
                if (subject == null) throw new IllegalArgumentException("Unregistered test target");
                return subject;
            }
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
    }

    public static ScmFeedbackFormatter formatter() {
        return formatter("team");
    }

    public static ScmFeedbackFormatter formatter(String slug) {
        return new ScmFeedbackFormatter(
                new ApplicationProperties(null, new ApplicationProperties.Webapp("https://hephaestus.example")),
                workspaces(slug),
                new WorkspaceSubdomainProperties(false, ""));
    }

    /** Writes the footer that v1.0.0-rc.1 posted: the same lines, without the disclosure marker above them. */
    public static ScmFeedbackFormatter formatterBeforeDisclosureMarker(String slug) {
        return new ScmFeedbackFormatter(
                new ApplicationProperties(null, new ApplicationProperties.Webapp("https://hephaestus.example")),
                workspaces(slug),
                new WorkspaceSubdomainProperties(false, "")) {
            @Override
            public Footer footer(ItemRef item) {
                var footer = super.footer(item);
                String marker = ScmFeedbackFormatter.DISCLOSURE + "\n";
                return new Footer(
                        footer.workspaceId(),
                        footer.summary().replace(marker, ""),
                        footer.lineNote().replace(marker, ""));
            }
        };
    }

    private static WorkspaceSummaryQuery workspaces(String slug) {
        return mock(WorkspaceSummaryQuery.class, call -> {
            if (call.getMethod().getName().equals("findById"))
                return Optional.of(new WorkspaceSummaryQuery.WorkspaceSummary(call.getArgument(0), slug, "Team"));
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
    }

    /**
     * A summary, a line note and a file note with fresh write identities, worded with the Markdown that posted text
     * escapes or keeps: a code span, a mention, an HTML tag and a list.
     */
    public static OnWorkPackage readbackPackage(String label, String path, int line) {
        String run = label + "-" + UUID.randomUUID();
        var summary = new SummaryContent(
                copyId(run + "-summary"),
                "Readback check " + label + ".\n\n- Keep `deactivateUser` <b>as is</b>, @alice.\n- A second point.",
                copyId(run + "-summary").toString(),
                marker(run + "-summary"));
        return new OnWorkPackage(
                summary,
                List.of(
                        line(
                                anchor(path, line),
                                "This line reads `SECRET_TOKEN` from <env>; " + label + ".",
                                marker(run + "-line"),
                                copyId(run + "-line").toString()),
                        line(
                                new FeedbackAnchor(path, null, null, null),
                                "This file has no test; " + label + ".",
                                marker(run + "-file"),
                                copyId(run + "-file").toString())));
    }

    private static String marker(String key) {
        return "<!-- hephaestus-feedback-copy:" + copyId(key) + " -->";
    }

    public static UUID copyId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static SummaryContent summary(String body, @Nullable String marker) {
        return new SummaryContent(
                copyId(body + marker), body, copyId(body + marker).toString(), marker);
    }

    public static LineNote line(FeedbackAnchor anchor, String body, String marker, String key) {
        return new LineNote(copyId(key), anchor, body, key, marker);
    }

    public static FeedbackAnchor anchor(String path, int line) {
        return anchor(path, line, null);
    }

    public static FeedbackAnchor anchor(String path, int line, @Nullable Integer start) {
        return new FeedbackAnchor(path, start, line, CitationSide.NEW);
    }

    public static FeedbackAnchor range(String path, int start, int end) {
        return new FeedbackAnchor(path, start, end, CitationSide.NEW);
    }
}
