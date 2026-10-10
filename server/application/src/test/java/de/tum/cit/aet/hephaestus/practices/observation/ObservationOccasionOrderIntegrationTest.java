package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Which run speaks for a claim, read back through Hibernate as every consumer reads it: the in-memory
 * {@link LatestRun} over rows flushed, cleared and loaded again, and the native latest-run selectors must agree, and
 * both order a run by the occasion it proved it read — never by its completion alone, and only for an author review of
 * work that was open when it was admitted.
 */
class ObservationOccasionOrderIntegrationTest extends BaseIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-09T08:39:48Z");
    private static final long PULL_REQUEST = 42L;
    private static final String ADMISSION = "ADMISSION";
    private static final String OPENED = ScmSignals.PULL_REQUEST_OPENED.value();
    private static final String EDITED = ScmSignals.PULL_REQUEST_EDITED.value();
    private static final String MANUAL_REVIEW = ScmSignals.PULL_REQUEST_MANUAL_REVIEW.value();

    @Autowired
    private AgentJobRepository jobs;

    @Autowired
    private ObservationRepository observations;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private PracticeRepository practices;

    @Autowired
    private PracticeRevisionRepository revisions;

    @Autowired
    private UserRepository users;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TransactionTemplate transactions;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private Workspace workspace;
    private Workspace elsewhere;
    private IdentityProvider provider;
    private final Map<Long, List<UUID>> recorded = new HashMap<>();
    private final Map<Long, Set<Long>> people = new HashMap<>();
    private long nextUser = 100L;

    /** How a source job was admitted and what its capture proved. */
    private record Admission(
            @Nullable String signal,
            @Nullable String retainedBasis,
            @Nullable Consumer<ObjectNode> role,
            @Nullable Consumer<ObjectNode> currentWork) {

        static Admission proved(String signal) {
            return new Admission(signal, ADMISSION, null, current -> current.put(CURRENT, true));
        }

        Admission basis(@Nullable String basis) {
            return new Admission(signal, basis, role, currentWork);
        }

        Admission role(Consumer<ObjectNode> role) {
            return new Admission(signal, retainedBasis, role, currentWork);
        }

        Admission currentWork(@Nullable Consumer<ObjectNode> currentWork) {
            return new Admission(signal, retainedBasis, role, currentWork);
        }
    }

    private static final String CURRENT = Observation.CURRENT_WORK_METADATA_KEY;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspace = workspaces.save(TestEntities.activeWorkspace("occasion-order-ws"));
        elsewhere = workspaces.save(TestEntities.activeWorkspace("occasion-order-elsewhere"));
        provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
    }

    /**
     * Each person's claim on one pull request is reviewed twice: the newer occasion's review completes first, the
     * older one's later. Only when both runs prove an author review of open work does the newer occasion speak.
     */
    @Test
    void shouldLetOnlyAProvedNewerOccasionOfOpenAuthorWorkSpeakOverALaterCompletion() {
        Practice describes = practice(workspace, "describes-the-change");
        Practice sizes = practice(workspace, "reviewable-diff-size");
        Set<UUID> expected = new HashSet<>();

        // Proved: the newer edit stands. The older run also read the other practice, which the newer one did not.
        User author = person(workspace);
        UUID[] proved = inversion(workspace, describes, author, Admission.proved(OPENED), Admission.proved(EDITED));
        expected.add(proved[1]);
        expected.add(observe(workspace, proved[2], sizes, author, "LIVE", T0.plus(minutes(50))));
        // An explicit author role is the author's review.
        expected.add(inversion(
                workspace,
                describes,
                person(workspace),
                Admission.proved(OPENED).role(role -> role.put("subject_role", "AUTHOR")),
                Admission.proved(EDITED).role(role -> role.put("subject_role", "AUTHOR")))[1]);
        // A manual request of open work is an occasion like any other.
        expected.add(inversion(
                workspace, describes, person(workspace), Admission.proved(OPENED), Admission.proved(MANUAL_REVIEW))[1]);
        expected.add(inversion(
                workspace,
                describes,
                person(workspace),
                Admission.proved(OPENED),
                new Admission(
                        null,
                        ADMISSION,
                        null,
                        current -> current.put(CURRENT, true).put("observation_origin", "MANUAL")))[1]);
        // Every other run keeps completion order.
        for (Admission unproved : List.of(
                Admission.proved(OPENED).basis(null),
                Admission.proved(OPENED).basis("CAPTURE"),
                Admission.proved(OPENED).currentWork(null),
                Admission.proved(OPENED).currentWork(current -> current.put(CURRENT, false)),
                Admission.proved(OPENED).currentWork(current -> current.put(CURRENT, "true")),
                Admission.proved(OPENED).currentWork(current -> current.put(CURRENT, 1)),
                Admission.proved(OPENED).role(role -> role.put("subject_role", "REVIEWER")),
                Admission.proved(OPENED).role(role -> role.put("subject_role", 1)),
                Admission.proved(ScmSignals.PULL_REQUEST_MERGED.value()),
                Admission.proved(ScmSignals.PULL_REQUEST_CLOSED.value()),
                Admission.proved(MANUAL_REVIEW).currentWork(current -> current.put(CURRENT, false)),
                new Admission(
                        null,
                        ADMISSION,
                        null,
                        current -> current.put(CURRENT, false).put("observation_origin", "MANUAL")),
                new Admission(null, ADMISSION, null, current -> current.put(CURRENT, true)))) {
            expected.add(inversion(workspace, describes, person(workspace), unproved, unproved)[0]);
        }
        // Another workspace's mirror of the same pull request id is its own claim, ordered the same way.
        Practice describesElsewhere = practice(elsewhere, "describes-the-change");
        User elsewhereAuthor = person(elsewhere);
        expected.add(inversion(
                elsewhere, describesElsewhere, elsewhereAuthor, Admission.proved(OPENED), Admission.proved(EDITED))[1]);
        expected.add(inversion(
                elsewhere,
                describesElsewhere,
                person(elsewhere),
                Admission.proved(OPENED).basis(null),
                Admission.proved(EDITED).basis(null))[0]);

        List<Observation> rows = reloaded();
        assertThat(ids(LatestRun.perClaim(rows))).isEqualTo(expected);
        assertThat(nativeLatestRows()).isEqualTo(expected);
        assertThat(rows.stream()
                        .filter(row -> row.getId().equals(proved[0]))
                        .findFirst()
                        .orElseThrow()
                        .getObservedAt())
                .as("completion stays the completion instant")
                .isEqualTo(T0.plus(minutes(50)));
    }

    @Test
    void shouldNeverReadAnotherWorkspacesSourceJobAsTheOccasion() {
        Practice describes = practice(workspace, "describes-the-change");
        User author = person(workspace);
        UUID foreignRun = job(elsewhere, T0, Admission.proved(OPENED));
        UUID rowId = observe(workspace, foreignRun, describes, author, "LIVE", T0.plus(minutes(50)));
        assertThat(reloaded()).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(rowId);
            assertThat(row.getOccasionAt()).isNull();
        });
        assertThat(observations.findForPersonHistory(author.getId(), workspace.getId()))
                .singleElement()
                .satisfies(row -> assertThat(row.getOccasionAt()).isNull());
    }

    @Test
    void shouldLoadTheDerivedOccasionThroughEveryNativeEntityReader() {
        Practice describes = practice(workspace, "describes-the-change");
        User author = person(workspace);
        UUID older = job(workspace, T0, Admission.proved(OPENED));
        UUID newer = job(workspace, T0.plus(minutes(11)), Admission.proved(EDITED));
        observe(workspace, older, describes, author, "LIVE", T0.plus(minutes(50)));
        observe(workspace, newer, describes, author, "LIVE", T0.plus(minutes(36)));
        List<Observation> loaded = reloaded();
        var occasions = loaded.stream().collect(Collectors.toMap(Observation::getId, Observation::getOccasionAt));
        long workspaceId = workspace.getId();
        long authorId = author.getId();
        var nativeReads = List.of(
                observations.findRecentByDeveloperAndWorkspace(
                        authorId, workspaceId, T0.minusSeconds(1), List.of("NOT_MET"), Pageable.unpaged()),
                observations.findEarlierRunsByDeveloperAndWorkspace(
                        authorId, workspaceId, T0.minusSeconds(1), Pageable.unpaged()),
                observations.findForPersonHistory(authorId, workspaceId),
                observations.findByDeveloperAndWorkspaceBetween(
                        authorId, workspaceId, T0.minusSeconds(1), T0.plus(minutes(60))),
                observations.findByWorkspaceBetween(
                        workspaceId, List.of(authorId), T0.minusSeconds(1), T0.plus(minutes(60))),
                observations.findLatestRunsByWorkspaceSince(workspaceId, List.of(authorId), T0.minusSeconds(1)));
        for (List<Observation> read : nativeReads) {
            assertThat(read).isNotEmpty();
            read.forEach(row -> assertThat(row.getOccasionAt()).isEqualTo(occasions.get(row.getId())));
        }
    }

    @Test
    void shouldKeepManualAndLiveTogetherForQuotesLiveAloneForTrendsAndBackfillApart() {
        Practice describes = practice(workspace, "describes-the-change");
        User author = person(workspace);
        UUID live = job(workspace, T0, Admission.proved(OPENED));
        UUID manual = job(workspace, T0.plus(minutes(20)), Admission.proved(MANUAL_REVIEW));
        UUID backfill = job(workspace, T0.plus(minutes(30)), Admission.proved(OPENED));
        UUID liveRow = observe(workspace, live, describes, author, "LIVE", T0.plus(minutes(40)));
        UUID manualRow = observe(workspace, manual, describes, author, "MANUAL", T0.plus(minutes(30)));
        UUID backfillRow = observe(workspace, backfill, describes, author, "BACKFILL", T0.plus(minutes(60)));

        List<Observation> rows = reloaded();
        assertThat(ids(LatestRun.perClaim(rows))).containsExactlyInAnyOrder(manualRow, backfillRow);
        assertThat(ids(LatestRun.perLiveClaim(rows))).containsExactly(liveRow);
        assertThat(nativeLatestRows()).containsExactlyInAnyOrder(manualRow, backfillRow);
        assertThat(observations.findLatestRunsByWorkspaceSince(workspace.getId(), List.of(author.getId()), T0).stream()
                        .map(Observation::getAgentJobId)
                        .collect(Collectors.toSet()))
                .isEqualTo(Set.of(manual, backfill, live));
    }

    /**
     * Two runs of one claim: the older occasion admitted at {@code T0} completing last, the newer at {@code T0+11m}
     * completing first. Returns the older run's row, the newer run's row, then the older run's id.
     */
    private UUID[] inversion(Workspace in, Practice practice, User person, Admission older, Admission newer) {
        UUID olderRun = job(in, T0, older);
        UUID newerRun = job(in, T0.plus(minutes(11)), newer);
        UUID olderRow = observe(in, olderRun, practice, person, "LIVE", T0.plus(minutes(50)));
        UUID newerRow = observe(in, newerRun, practice, person, "LIVE", T0.plus(minutes(36)));
        return new UUID[] {olderRow, newerRow, olderRun};
    }

    /** The rows each person's native window keeps; every row this test records is a lapse. */
    private Set<UUID> nativeLatestRows() {
        Set<UUID> rows = new HashSet<>();
        people.forEach((workspaceId, ids) -> ids.forEach(person -> observations
                .findRecentByDeveloperAndWorkspace(
                        person, workspaceId, T0.minus(Duration.ofDays(1)), List.of("NOT_MET"), Pageable.unpaged())
                .forEach(row -> rows.add(row.getId()))));
        return rows;
    }

    /**
     * Every row this test recorded, after the persistence context is flushed and cleared, so each is loaded again and
     * its derived occasion read from the source job rather than from a cached instance.
     */
    private List<Observation> reloaded() {
        return Objects.requireNonNull(transactions.execute(status -> {
            EntityManager managed = Objects.requireNonNull(entityManager);
            managed.flush();
            managed.clear();
            List<Observation> rows = new ArrayList<>();
            recorded.forEach(
                    (workspaceId, ids) -> rows.addAll(observations.findAllByIdInAndWorkspaceId(ids, workspaceId)));
            return rows;
        }));
    }

    private static Set<UUID> ids(List<Observation> rows) {
        return rows.stream().map(Observation::getId).collect(Collectors.toSet());
    }

    private static Duration minutes(long minutes) {
        return Duration.ofMinutes(minutes);
    }

    private User person(Workspace in) {
        long id = nextUser++;
        User person = users.save(TestUserFactory.createUser(id, "person-" + id, provider));
        people.computeIfAbsent(in.getId(), ignored -> new HashSet<>()).add(person.getId());
        return person;
    }

    private UUID job(Workspace in, Instant createdAt, Admission admission) {
        var job = new AgentJob();
        job.setWorkspace(in);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("occasion-order-worker");
        job.setConfigSnapshot(mapper.createObjectNode());
        ObjectNode metadata = mapper.createObjectNode().put("pull_request_id", PULL_REQUEST);
        String signal = admission.signal();
        Consumer<ObjectNode> role = admission.role();
        Consumer<ObjectNode> currentWork = admission.currentWork();
        String retainedBasis = admission.retainedBasis();
        if (signal != null) metadata.put("signal", signal);
        if (role != null) role.accept(metadata);
        if (currentWork != null) currentWork.accept(metadata);
        job.setMetadata(metadata);
        ObjectNode work = mapper.createObjectNode()
                .put("artifactKind", ArtifactKinds.PULL_REQUEST.value())
                .put("artifactId", PULL_REQUEST);
        if (retainedBasis != null) work.put("retainedBasis", retainedBasis);
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.set("reviewedWork", work);
        job.setEvidenceSnapshot(snapshot);
        job.setCreatedAt(createdAt);
        return jobs.saveAndFlush(job).getId();
    }

    private UUID observe(Workspace in, UUID run, Practice practice, User person, String origin, Instant observedAt) {
        UUID id = UUID.randomUUID();
        observations.insertIfAbsent(
                id,
                "occasion-order-" + id,
                run,
                in.getId(),
                practice.getId(),
                null,
                ArtifactKinds.PULL_REQUEST.value(),
                PULL_REQUEST,
                person.getId(),
                "Summary",
                "NOT_MET",
                "MINOR",
                "{\"citations\":[]}",
                null,
                null,
                observedAt,
                origin);
        recorded.computeIfAbsent(in.getId(), ignored -> new ArrayList<>()).add(id);
        return id;
    }

    private Practice practice(Workspace in, String slug) {
        var practice = new Practice();
        PracticeTestEvidence.configure(practice, ArtifactKinds.PULL_REQUEST);
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(in);
        practice.setSlug(slug);
        practice.setName(slug);
        practice.setCriteria("The definition");
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
        var saved = practices.saveAndFlush(practice);
        saved.setCurrentRevision(revisions.save(new PracticeRevision(saved, 1)));
        return practices.saveAndFlush(saved);
    }
}
