package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyForm;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.practices.feedback.AbstractFeedbackDeliveryIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackProviderReadback;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackOwnCopyEditor;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackReadbackMeters;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.PostedCopyCorrector;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GitLabLineNoteWriterIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    enum MergeRequestCase {
        OPEN,
        REJECTED_POSITION,
        MERGED_WITHOUT_BRANCH,
        OPEN_WITHOUT_BRANCH
    }

    @ParameterizedTest
    @EnumSource(MergeRequestCase.class)
    void shouldPublishTheCompleteCheckedPackageOrRecordWhyItStopped(MergeRequestCase scenario) {
        String base = "a".repeat(40);
        String head = "c".repeat(40);
        String start = "b".repeat(40);
        target = new FeedbackTarget(
                target.item(),
                new IntegrationRef(IntegrationKind.GITLAB, workspace.getId(), null),
                ScmVersion.pullRequest(base, head, "Test", null));
        var guard = mock(OutboundEgressGuard.class);
        var targets = mock(ScmFeedbackTargetResolver.class);
        when(targets.subject(target, IntegrationKind.GITLAB)).thenReturn("group/project!42");
        var gitlab = new ScriptedGitLabNotes(guard, targets);
        gitlab.rejectPositions(scenario == MergeRequestCase.REJECTED_POSITION);
        gitlab.head(42, head);
        if (scenario == MergeRequestCase.MERGED_WITHOUT_BRANCH) gitlab.merge(42);
        if (scenario == MergeRequestCase.MERGED_WITHOUT_BRANCH || scenario == MergeRequestCase.OPEN_WITHOUT_BRANCH)
            gitlab.deleteBranch(42);
        var tokens = mock(GitLabTokenService.class);
        when(tokens.resolveServerUrl(workspace.getId())).thenReturn("https://gitlab.example.test");
        when(tokens.getAccessToken(workspace.getId())).thenReturn("synthetic-token");
        var reads = new AtomicInteger();
        var reader = new GitLabDiffVersionReader(
                tokens,
                () -> WebClient.builder().exchangeFunction(request -> {
                    assertThat(request.url().getRawPath()).contains("group%2Fproject/merge_requests/42/versions");
                    String version = "{\"id\":1,\"state\":\"collected\",\"base_commit_sha\":\"" + base
                            + "\",\"head_commit_sha\":\"" + head
                            + "\",\"start_commit_sha\":\"" + start
                            + "\",\"diffs\":[{\"old_path\":\"src/A.java\",\"new_path\":\"src/Renamed.java\",\"renamed_file\":true,\"diff\":\"@@ -10,3 +10,3 @@\\n context\\n-old\\n+new\\n last\\n\"}]}";
                    reads.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(
                                    request.url().getPath().endsWith("/1")
                                            ? version
                                            : "[{\"id\":2,\"base_commit_sha\":\"" + base + "\",\"head_commit_sha\":\""
                                                    + "d".repeat(40) + "\",\"start_commit_sha\":\"" + start + "\"},"
                                                    + version
                                                    + "]")
                            .build());
                }));
        var writer = new GitLabLineNoteWriter(
                gitlab.provider(),
                new GitLabNotesApi(gitlab.provider(), () -> gitlab.rest(), tokens, guard),
                tokens,
                guard,
                targets,
                reader,
                ScmFeedbackFixtures.formatter());
        var resolution = mock(FeedbackTargetResolver.class);
        when(resolution.resolve(any())).thenReturn(target);
        when(resolution.resolveExisting(any())).thenReturn(target);
        var corrector =
                new PostedCopyCorrector(copies, visibility, List.of(gitlab.channel()), List.of(writer), transactions);
        sender = sender(new FeedbackSendService(
                transactions,
                resolution,
                List.of(gitlab.channel()),
                List.of(writer),
                corrector,
                new FeedbackReadbackMeters(new SimpleMeterRegistry()),
                mock(FeedbackProviderReadback.class),
                mock(FeedbackOwnCopyEditor.class),
                gate,
                mock(SilentModeQuery.class),
                Clock.systemUTC()));
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        if (scenario == MergeRequestCase.OPEN_WITHOUT_BRANCH) {
            assertThat(current().getState()).isEqualTo(FeedbackState.WITHHELD);
            assertThat(current().getStopReason()).isEqualTo(FeedbackStopReason.SOURCE_BRANCH_MISSING);
            assertThat(children()).hasSize(2).allSatisfy(copy -> {
                assertThat(copy.getState()).isEqualTo(FeedbackCopyState.WITHHELD);
                assertThat(copy.getStopReason()).isEqualTo(FeedbackStopReason.SOURCE_BRANCH_MISSING);
                assertThat(copy.getWriteStartedAt()).isNull();
            });
            assertThat(gitlab.bulkPublications()).isZero();
            assertThat(reads.get()).isZero();
            return;
        }
        if (scenario == MergeRequestCase.REJECTED_POSITION) {
            assertThat(current().getState()).isNotEqualTo(FeedbackState.DELIVERED);
            assertThat(gitlab.threads().on(ScriptedGitLabNotes.mergeRequest(42)))
                    .isEmpty();
            assertThat(gitlab.bulkPublications()).isZero();
            return;
        }
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(gitlab.bulkPublications()).isEqualTo(1);
        var copy = children().get(1);
        assertThat(copy.getKind()).isEqualTo(FeedbackCopyKind.LINE_NOTE);
        assertThat(copy.getWriteStartedAt()).isNotNull();
        assertThat(reads.get()).isEqualTo(2);
        var note = new LineNote(
                copy.getId(),
                new FeedbackAnchor(
                        Objects.requireNonNull(copy.getPath()), copy.getStartLine(), copy.getEndLine(), copy.getSide()),
                Objects.requireNonNull(copy.getBody()),
                Objects.requireNonNull(copy.getWriteKey()),
                Objects.requireNonNull(copy.getWriteMarker()));
        assertThat(writer.findPosted(target, new OnWorkPackage(null, List.of(note))))
                .singleElement()
                .satisfies(receipt -> {
                    assertThat(receipt.externalRef()).isEqualTo(copy.getExternalRef());
                    assertThat(receipt.actualKind()).isEqualTo(CopyForm.LINE_NOTE);
                });
        assertThat(gitlab.threads().on(ScriptedGitLabNotes.mergeRequest(42))).hasSize(2);
        // The old-side range 10-12 is one native range thread.
        assertThat(copy.getExternalRef()).startsWith("gid://gitlab/DiffNote/");
        assertThat(gitlab.threads().on(ScriptedGitLabNotes.mergeRequest(42)))
                .filteredOn(comment -> comment.id().equals(copy.getExternalRef()))
                .singleElement()
                .satisfies(comment -> assertThat(comment.body()).contains(GitLabLineNoteWriter.RANGE_PLACEMENT));
    }
}
