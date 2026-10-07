package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.ReviewTargetQuery;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class PublicReviewEligibilityTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long PULL_REQUEST_ID = 55L;
    private static final long ISSUE_ID = 66L;
    private static final long REPOSITORY_ID = 3L;
    private static final String REPOSITORY = "team/app";
    private static final int NUMBER = 12;
    /** Internal {@code User.id} values, the identity the mirrored work and every observation carry. */
    private static final long AUTHOR_ID = 101L;

    private static final long REVIEWER_ID = 202L;
    private static final String SLUG = "explains-the-change";
    private static final String PRIVATE_HISTORY = "hephaestus.observation-history";

    @Mock
    private ReviewTargetQuery reviewTargets;

    @Mock
    private RepositoryToMonitorRepository monitoredRepositories;

    @Mock
    private ObservationRepository observations;

    @Mock
    private FeedbackRepository feedback;

    @Mock
    private FeedbackObservationRepository feedbackObservations;

    private PublicReviewEligibility eligibility;
    private AgentJob job;

    @BeforeEach
    void setUp() {
        eligibility = new PublicReviewEligibility(
                reviewTargets, monitoredRepositories, observations, feedback, feedbackObservations);
        job = job(ArtifactKinds.PULL_REQUEST, pullRequestMetadata());
        lenient().when(reviewTargets.findPullRequest(PULL_REQUEST_ID)).thenReturn(Optional.of(target(AUTHOR_ID)));
        lenient().when(reviewTargets.findIssue(ISSUE_ID)).thenReturn(Optional.of(target(AUTHOR_ID)));
        lenient()
                .when(monitoredRepositories.existsByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, REPOSITORY))
                .thenReturn(true);
    }

    @Test
    void shouldAdmitAnAuthorObservationAboutTheMirroredAuthorWhenItsSourcesArePublic() {
        Observation diff = authorObservation().build();
        Observation engagement = authorObservation()
                .evidence(citing(ReviewThreadContentSource.KIND.value()))
                .build();

        assertThat(eligibility.publicObservationIds(job, List.of(diff, engagement)))
                .containsExactlyInAnyOrder(diff.getId(), engagement.getId());
    }

    @Test
    void shouldAdmitAnIssueAuthorObservationWhenTheIssueIsMirroredUnderTheJob() {
        job = job(ArtifactKinds.ISSUE, issueMetadata());
        Observation onIssue = authorObservation()
                .artifactKind(ArtifactKinds.ISSUE)
                .artifactId(ISSUE_ID)
                .practiceRevision(revision(ActorRole.AUTHOR, ArtifactKinds.ISSUE))
                .build();

        assertThat(eligibility.publicObservationIds(job, List.of(onIssue))).containsExactly(onIssue.getId());
    }

    @Test
    void shouldAdmitAMergerObservationOnlyWhenTheAuthorMergedTheWork() {
        Observation merger = authorObservation()
                .practiceRevision(revision(ActorRole.MERGER, ArtifactKinds.PULL_REQUEST))
                .build();

        job.setMetadata(pullRequestMetadata().put("author_id", AUTHOR_ID).put("merged_by_id", AUTHOR_ID));
        assertThat(eligibility.publicObservationIds(job, List.of(merger))).containsExactly(merger.getId());

        job.setMetadata(pullRequestMetadata().put("author_id", AUTHOR_ID).put("merged_by_id", REVIEWER_ID));
        assertThat(eligibility.publicObservationIds(job, List.of(merger))).isEmpty();
    }

    @Test
    void shouldKeepAReviewerObservationPrivateWhenTheReviewerAlsoWroteTheWork() {
        Observation aboutReviewer = authorObservation()
                .practiceRevision(revision(ActorRole.REVIEWER, ArtifactKinds.PULL_REQUEST))
                .aboutUserId(REVIEWER_ID)
                .build();
        Observation selfReview = authorObservation()
                .practiceRevision(revision(ActorRole.REVIEWER, ArtifactKinds.PULL_REQUEST))
                .build();

        assertThat(eligibility.publicObservationIds(job, List.of(aboutReviewer, selfReview)))
                .isEmpty();
    }

    @Test
    void shouldAdmitNothingWhenTheJobIsAboutAReviewerEvenIfTheReviewerWroteTheWork() {
        job.setMetadata(pullRequestMetadata().put("subject_role", "REVIEWER").put("about_user_id", AUTHOR_ID));

        assertThat(eligibility.publicObservationIds(
                        job, List.of(authorObservation().build())))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVIEWER", "MERGER", "ASSIGNEE", "author", ""})
    void shouldAdmitNothingWhenTheJobNamesAnyRoleOtherThanAuthor(String role) {
        job.setMetadata(pullRequestMetadata().put("subject_role", role));

        assertThat(eligibility.publicObservationIds(
                        job, List.of(authorObservation().build())))
                .isEmpty();
    }

    @Test
    void shouldAdmitNothingWhenTheJobRoleIsExplicitlyNull() {
        job.setMetadata(pullRequestMetadata().putNull("subject_role"));

        assertThat(eligibility.publicObservationIds(
                        job, List.of(authorObservation().build())))
                .isEmpty();
    }

    @Test
    void shouldTreatAJobWithoutARoleAsAnAuthorJobWhenItWasRecordedBeforeRolesExisted() {
        Observation legacy = authorObservation().build();
        assertThat(eligibility.publicObservationIds(job, List.of(legacy))).containsExactly(legacy.getId());

        job.setMetadata(pullRequestMetadata().put("subject_role", "AUTHOR"));
        assertThat(eligibility.publicObservationIds(job, List.of(legacy))).containsExactly(legacy.getId());
    }

    static Stream<Arguments> observationsOutsideTheAuthorsPublicRecord() {
        return Stream.of(
                outside("about somebody else", b -> b.aboutUserId(REVIEWER_ID)),
                outside("without its original revision", b -> b.practiceRevision(null)),
                outside(
                        "under an assignee revision",
                        b -> b.practiceRevision(revision(ActorRole.ASSIGNEE, ArtifactKinds.PULL_REQUEST))),
                outside("from another job", b -> b.agentJobId(UUID.randomUUID())),
                outside("from another workspace", b -> b.workspaceId(WORKSPACE_ID + 1)),
                outside("on another work item", b -> b.artifactId(PULL_REQUEST_ID + 1)),
                outside("on another kind of work", b -> b.artifactKind(ArtifactKinds.ISSUE)),
                outside("citing private history", b -> b.evidence(citing(PRIVATE_HISTORY))),
                outside("having consulted private history", b -> b.evidence(consulting(PRIVATE_HISTORY))),
                outside("without recorded provenance", b -> b.evidence(null)),
                outside("naming no source", b -> b.evidence(JsonNodeFactory.instance.objectNode())));
    }

    @ParameterizedTest
    @MethodSource("observationsOutsideTheAuthorsPublicRecord")
    void shouldKeepAnObservationPrivateWhenItIsOutsideTheAuthorsPublicRecord(
            UnaryOperator<Observation.ObservationBuilder> change) {
        Observation outside = change.apply(authorObservation()).build();
        Observation inside = authorObservation().build();

        assertThat(eligibility.publicObservationIds(job, List.of(outside, inside)))
                .containsExactly(inside.getId());
    }

    static Stream<Arguments> unresolvedAuthors() {
        return Stream.of(
                unresolved("the work is no longer mirrored", Optional.empty(), true),
                unresolved("the mirrored work has no author", Optional.of(target(null)), true),
                unresolved(
                        "the mirrored work was deleted",
                        Optional.of(new ReviewTargetQuery.Target(REPOSITORY_ID, REPOSITORY, NUMBER, AUTHOR_ID, true)),
                        true),
                unresolved(
                        "the mirrored work is in another repository",
                        Optional.of(new ReviewTargetQuery.Target(
                                REPOSITORY_ID + 1, "team/other", NUMBER, AUTHOR_ID, false)),
                        true),
                unresolved(
                        "the mirrored work has another number",
                        Optional.of(
                                new ReviewTargetQuery.Target(REPOSITORY_ID, REPOSITORY, NUMBER + 1, AUTHOR_ID, false)),
                        true),
                unresolved("the repository is not monitored by the workspace", Optional.of(target(AUTHOR_ID)), false));
    }

    @ParameterizedTest
    @MethodSource("unresolvedAuthors")
    void shouldAdmitNothingWhenTheArtifactAuthorCannotBeResolvedForThisWork(
            Optional<ReviewTargetQuery.Target> mirrored, boolean monitored) {
        lenient().when(reviewTargets.findPullRequest(PULL_REQUEST_ID)).thenReturn(mirrored);
        lenient()
                .when(monitoredRepositories.existsByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, REPOSITORY))
                .thenReturn(monitored);

        assertThat(eligibility.publicObservationIds(
                        job, List.of(authorObservation().build())))
                .isEmpty();
    }

    @Test
    void shouldAdmitNothingWhenTheJobDoesNotNameItsWork() {
        job.setMetadata(pullRequestMetadata().without("pull_request_id"));

        assertThat(eligibility.publicObservationIds(
                        job, List.of(authorObservation().build())))
                .isEmpty();
        verify(reviewTargets, never()).findPullRequest(PULL_REQUEST_ID);
    }

    @Test
    void shouldPermitAPersistedPackageWhenEverySupportIsAboutTheAuthor() {
        Observation support = authorObservation().build();
        Feedback stored = storedFeedback().build();
        stubStoredPackage(stored, support);

        assertThat(eligibility.permitsDelivery(job, stored.getId(), List.of(SLUG), Set.of(support.getId())))
                .isTrue();
    }

    static Stream<Arguments> packagesNotAddressedToTheAuthor() {
        return Stream.of(
                addressed("to the reviewer", b -> b.recipientUserId(REVIEWER_ID)),
                addressed("about the reviewer", b -> b.aboutUserId(REVIEWER_ID)),
                addressed("to a private channel", b -> b.channel(FeedbackChannel.IN_APP)),
                addressed("from another job", b -> b.agentJobId(UUID.randomUUID())),
                addressed("on another work item", b -> b.artifactId(PULL_REQUEST_ID + 1)),
                addressed("on another kind of work", b -> b.artifactKind(ArtifactKinds.ISSUE)));
    }

    @ParameterizedTest
    @MethodSource("packagesNotAddressedToTheAuthor")
    void shouldRefuseAPersistedPackageWhenItIsNotAddressedToTheAuthor(UnaryOperator<Feedback.FeedbackBuilder> change) {
        Feedback stored = change.apply(storedFeedback()).build();
        when(feedback.findByIdAndWorkspaceId(stored.getId(), WORKSPACE_ID)).thenReturn(Optional.of(stored));

        assertThat(eligibility.permitsDelivery(job, stored.getId(), List.of(SLUG), null))
                .isFalse();
        verify(feedbackObservations, never()).findForVisibility(any(), anyCollection());
    }

    @Test
    void shouldRefuseAPersistedPackageWhenItNoLongerExistsInTheWorkspace() {
        UUID missing = UUID.randomUUID();
        when(feedback.findByIdAndWorkspaceId(missing, WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThat(eligibility.permitsDelivery(job, missing, List.of(SLUG), null))
                .isFalse();
    }

    @Test
    void shouldRefuseAnAuthorPackageWhenItsFrozenSupportIncludesAReviewerObservation() {
        Observation author = authorObservation().build();
        Observation reviewer = authorObservation()
                .practiceRevision(revision(ActorRole.REVIEWER, ArtifactKinds.PULL_REQUEST))
                .aboutUserId(REVIEWER_ID)
                .build();
        Feedback stored = storedFeedback().build();
        stubStoredPackage(stored, author, reviewer);

        assertThat(eligibility.permitsDelivery(job, stored.getId(), List.of(SLUG), null))
                .isFalse();
    }

    @Test
    void shouldRefuseAPersistedPackageWhenItsCitedObservationsDifferFromItsBoundSupport() {
        Observation support = authorObservation().build();
        Feedback stored = storedFeedback().build();
        stubStoredPackage(stored, support);

        assertThat(eligibility.permitsDelivery(
                        job, stored.getId(), List.of(SLUG), Set.of(support.getId(), UUID.randomUUID())))
                .isFalse();
    }

    @Test
    void shouldRefuseAPersistedPackageWhenTheJobIsAboutAReviewer() {
        job.setMetadata(pullRequestMetadata().put("subject_role", "REVIEWER").put("about_user_id", REVIEWER_ID));

        assertThat(eligibility.permitsDelivery(job, UUID.randomUUID(), List.of(SLUG), null))
                .isFalse();
        verify(feedback, never()).findByIdAndWorkspaceId(any(), any());
    }

    @Test
    void shouldPermitAnUnboundPackageOnlyWhenEveryCitedObservationIsFoundAndAboutTheAuthor() {
        Observation support = authorObservation().build();
        UUID missing = UUID.randomUUID();
        when(observations.findAllByIdInAndWorkspaceId(Set.of(support.getId()), WORKSPACE_ID))
                .thenReturn(List.of(support));
        when(observations.findAllByIdInAndWorkspaceId(Set.of(support.getId(), missing), WORKSPACE_ID))
                .thenReturn(List.of(support));

        assertThat(eligibility.permitsDelivery(job, null, List.of(SLUG), Set.of(support.getId())))
                .isTrue();
        assertThat(eligibility.permitsDelivery(job, null, List.of(SLUG), Set.of(support.getId(), missing)))
                .isFalse();
    }

    @Test
    void shouldRefuseAnUnboundPackageWhenItCitesNothing() {
        assertThat(eligibility.permitsDelivery(job, null, List.of(SLUG), Set.of()))
                .isFalse();
        verify(observations, never()).findAllByIdInAndWorkspaceId(anyCollection(), any());
    }

    @Test
    void shouldPermitALegacyPackageOnlyWhenEveryPracticeItNamesHasAuthorSupport() {
        Observation support = authorObservation().build();
        when(observations.findByAgentJobId(job.getId(), WORKSPACE_ID)).thenReturn(List.of(support));

        assertThat(eligibility.permitsDelivery(job, null, List.of(SLUG), null)).isTrue();
        assertThat(eligibility.permitsDelivery(job, null, List.of(SLUG, "names-the-issue"), null))
                .isFalse();
    }

    private void stubStoredPackage(Feedback stored, Observation... support) {
        when(feedback.findByIdAndWorkspaceId(stored.getId(), WORKSPACE_ID)).thenReturn(Optional.of(stored));
        List<FeedbackObservationRepository.FeedbackObservationVisibility> rows = Stream.of(support)
                .map(observation -> {
                    var row = mock(FeedbackObservationRepository.FeedbackObservationVisibility.class);
                    when(row.getObservation()).thenReturn(observation);
                    return row;
                })
                .toList();
        when(feedbackObservations.findForVisibility(WORKSPACE_ID, Set.of(stored.getId())))
                .thenReturn(rows);
    }

    @Test
    void shouldKeepReviewerHistoryPrivateEvenWhenRecipientAndAuthorAreTheSamePerson() {
        Feedback prior = storedFeedback().workspaceId(WORKSPACE_ID).build();
        Observation author = authorObservation().build();
        Observation selfReview = authorObservation()
                .practiceRevision(revision(ActorRole.REVIEWER, ArtifactKinds.PULL_REQUEST))
                .build();
        assertThat(eligibility.permitsPublicHistory(prior, List.of(author))).isTrue();
        assertThat(eligibility.permitsPublicHistory(prior, List.of(selfReview))).isFalse();
        assertThat(eligibility.permitsPublicHistory(prior, List.of(author, selfReview)))
                .isFalse();
        assertThat(eligibility.permitsPublicHistory(prior, List.of())).isFalse();
    }

    @Test
    void shouldAuthorizeOnlyExplicitAuthorSupportsWithoutUnrelatedSamePracticeReviewerRows() {
        Observation author = authorObservation().build();
        Observation reviewer = authorObservation()
                .practiceRevision(revision(ActorRole.REVIEWER, ArtifactKinds.PULL_REQUEST))
                .build();
        when(observations.findAllByIdInAndWorkspaceId(Set.of(author.getId()), WORKSPACE_ID))
                .thenReturn(List.of(author));
        assertThat(eligibility.permitsDelivery(job, null, List.of(SLUG), Set.of(author.getId())))
                .isTrue();
        when(observations.findAllByIdInAndWorkspaceId(Set.of(author.getId(), reviewer.getId()), WORKSPACE_ID))
                .thenReturn(List.of(author, reviewer));
        assertThat(eligibility.permitsDelivery(job, null, List.of(SLUG), Set.of(author.getId(), reviewer.getId())))
                .isFalse();
        Observation abstention = authorObservation()
                .outcome(Outcome.NOT_APPLICABLE)
                .severity(null)
                .build();
        when(observations.findAllByIdInAndWorkspaceId(Set.of(abstention.getId()), WORKSPACE_ID))
                .thenReturn(List.of(abstention));
        assertThat(eligibility.permitsDelivery(job, null, List.of(SLUG), Set.of(abstention.getId())))
                .isFalse();
    }

    private Observation.ObservationBuilder authorObservation() {
        PracticeRevision revision = revision(ActorRole.AUTHOR, ArtifactKinds.PULL_REQUEST);
        return Observation.builder()
                .id(UUID.randomUUID())
                .occurrenceKey(UUID.randomUUID().toString())
                .agentJobId(job.getId())
                .workspaceId(WORKSPACE_ID)
                .practice(revision.getPractice())
                .practiceRevision(revision)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(PULL_REQUEST_ID)
                .aboutUserId(AUTHOR_ID)
                .summary("The description leaves the change unexplained.")
                .outcome(Outcome.NOT_MET)
                .severity(Severity.MINOR)
                .evidence(citing(PullRequestContentSource.DIFF.value()));
    }

    private Feedback.FeedbackBuilder storedFeedback() {
        return Feedback.builder()
                .id(UUID.randomUUID())
                .agentJobId(job.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(PULL_REQUEST_ID)
                .recipientUserId(AUTHOR_ID)
                .aboutUserId(AUTHOR_ID)
                .channel(FeedbackChannel.IN_CONTEXT);
    }

    private static AgentJob job(ArtifactKind kind, ObjectNode metadata) {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setWorkspace(workspace);
        job.setArtifactKind(kind);
        job.setMetadata(metadata);
        return job;
    }

    private static ObjectNode pullRequestMetadata() {
        return JsonNodeFactory.instance
                .objectNode()
                .put("pull_request_id", PULL_REQUEST_ID)
                .put("pr_number", NUMBER)
                .put("repository_id", REPOSITORY_ID)
                .put("repository_full_name", REPOSITORY);
    }

    private static ObjectNode issueMetadata() {
        return JsonNodeFactory.instance
                .objectNode()
                .put("issue_id", ISSUE_ID)
                .put("issue_number", NUMBER)
                .put("repository_id", REPOSITORY_ID)
                .put("repository_full_name", REPOSITORY);
    }

    private static ReviewTargetQuery.Target target(@Nullable Long authorId) {
        return new ReviewTargetQuery.Target(REPOSITORY_ID, REPOSITORY, NUMBER, authorId, false);
    }

    private static PracticeRevision revision(ActorRole subject, ArtifactKind kind) {
        Practice practice = new Practice();
        practice.setSlug(SLUG);
        practice.setName("Explain the change");
        PracticeTestEvidence.configure(practice, kind);
        practice.setCriteria("The description explains what changed and why.");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(kind));
        practice.setSubject(subject);
        return new PracticeRevision(practice, 1);
    }

    private static JsonNode citing(String sourceKind) {
        ObjectNode evidence = JsonNodeFactory.instance.objectNode();
        evidence.putArray("citations").addObject().put("sourceKind", sourceKind);
        return evidence;
    }

    private static JsonNode consulting(String sourceKind) {
        ObjectNode evidence = (ObjectNode) citing(PullRequestContentSource.DIFF.value());
        evidence.putObject("search").putArray("consulted").add(sourceKind);
        return evidence;
    }

    private static Arguments outside(String name, UnaryOperator<Observation.ObservationBuilder> change) {
        return arguments(Named.of(name, change));
    }

    private static Arguments addressed(String name, UnaryOperator<Feedback.FeedbackBuilder> change) {
        return arguments(Named.of(name, change));
    }

    private static Arguments unresolved(String name, Optional<ReviewTargetQuery.Target> mirrored, boolean monitored) {
        return arguments(Named.of(name, mirrored), monitored);
    }
}
