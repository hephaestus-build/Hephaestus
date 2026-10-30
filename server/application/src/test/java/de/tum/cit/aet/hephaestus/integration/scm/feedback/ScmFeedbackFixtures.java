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
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.nio.charset.StandardCharsets;
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
        long id = NEXT.incrementAndGet();
        boolean issue =
                subject.contains("/issues/") || (integration.kind().name().equals("GITLAB") && subject.contains("#"));
        ItemRef item = new ItemRef(
                integration.workspaceId(), id, new ItemKind(issue ? "scm.issue" : "scm.pull_request"), id, null, null);
        SUBJECTS.put(item, subject);
        ItemVersion version = head == null
                ? new ItemVersion("test-unpinned", null)
                : ScmVersion.pullRequest("a".repeat(40), head, "Test", null);
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
        var workspaces = mock(WorkspaceSummaryQuery.class, call -> {
            if (call.getMethod().getName().equals("findById"))
                return Optional.of(new WorkspaceSummaryQuery.WorkspaceSummary(call.getArgument(0), slug, "Team"));
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
        return new ScmFeedbackFormatter(
                new ApplicationProperties(null, new ApplicationProperties.Webapp("https://hephaestus.example")),
                workspaces,
                new WorkspaceSubdomainProperties(false, ""));
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
