package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SubmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.PostedText;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.ScriptedGitLabNotes;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A problem that a note on a GitLab merge request named, that a later review found No longer present, and that came
 * back. Admission, the thread resolver, the send state machine and the GitLab adapter are the application's own; only
 * GitLab's transport is scripted ({@code ScriptedGitLabTransport}).
 */
class GitLabReturningProblemContractIntegrationTest extends BaseIntegrationTest {
    private static final String RECORD = "context/pull-request.md";
    private static final String CHANGE = "context/change.json";
    private static final String PATH = "src/Export.java";
    private static final String DIFF = "scm.pull-request.diff";
    private static final String BASE = "b".repeat(40);
    private static final String START = "5".repeat(40);
    private static final String QUOTE = "try { export(); } catch (Exception e) { return; }";
    private static final String FIXED = "try { export(); } catch (Exception e) { throw new ExportFailed(e); }";
    private static final String SUMMARY = "Keep the failure cause when the export handler gives up.";
    private static final String NOTE = "Rethrow the failure here, or record the export as failed.";

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ReviewAdmissionData admissions;

    @Autowired
    private ResultAdmissionService admission;

    @Autowired
    private JobEvidenceFiles files;

    @Autowired
    private PersonDataCopyRecorder recorder;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private FeedbackCopyRepository copies;

    @Autowired
    private FeedbackRepository feedback;

    @Autowired
    private ScmItemService itemSource;

    @Autowired
    private ItemRepository items;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private CredentialBundleConverter credentials;

    @Autowired
    private InstanceSettingsService settings;

    @Autowired
    private ActionExecutor executor;

    @Autowired
    private ScriptedGitLabNotes gitlab;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper mapper;

    private boolean silencedBefore;

    @BeforeEach
    void deliverPublicCopies() {
        silencedBefore = settings.get().isSilentModeEngaged();
        silence(false);
    }

    @AfterEach
    void restoreSilentMode() {
        silence(silencedBefore);
    }

    @Test
    void shouldRaiseAReturningProblemInANewNoteAndLeaveTheResolvedGitLabThreadAloneWhenItsProblemComesBack() {
        var work = mergeRequest();
        String mergeRequest = ScriptedGitLabNotes.mergeRequest(work.iid());

        // Review 1: the problem is new. The summary and a diff note on the problem reach the merge request.
        UUID first;
        try (var review = claimed(work, "1".repeat(40), 30, QUOTE)) {
            var admitted = admitStaged(review, notMet(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            admission.publishPackage(review.identity(), composition(admitted, context));
            ActionTestSupport.drain(executor, jdbc);
            first = FeedbackIds.onWork(work.workspaceId(), review.reviewId(), work.developerId());
        }
        assertThat(feedback.findByIdAndWorkspaceId(first, work.workspaceId()))
                .hasValueSatisfying(row -> assertThat(row.getState()).isEqualTo(FeedbackState.DELIVERED));
        var note = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(work.workspaceId(), first).stream()
                .filter(copy -> copy.getKind() == FeedbackCopyKind.LINE_NOTE)
                .findFirst()
                .orElseThrow();
        assertThat(note.getState()).isEqualTo(FeedbackCopyState.POSTED);
        String thread = Objects.requireNonNull(note.getThreadRef());
        String t1 = Objects.requireNonNull(note.getExternalRef());
        assertThat(gitlab.threads().on(mergeRequest))
                .anySatisfy(comment -> assertThat(comment.body()).contains(PostedText.of(SUMMARY)))
                .anySatisfy(comment -> {
                    assertThat(comment.id()).isEqualTo(t1).startsWith("gid://gitlab/DiffNote/");
                    assertThat(comment.body()).contains(PostedText.of(NOTE));
                });
        String t1Body = body(mergeRequest, t1);
        assertThat(gitlab.resolved(t1)).isFalse();

        // Review 2: the cited code is gone and the review finds no problem. The resolver resolves T1 on GitLab.
        try (var review = claimed(work, "2".repeat(40), 20, FIXED)) {
            admitStaged(review, met(review));
        }
        String lane = "feedback-thread:" + resolution(work, thread);
        ActionTestSupport.due(jdbc, lane);
        ActionTestSupport.runLane(executor, jdbc, lane);
        assertThat(gitlab.resolved(t1)).isTrue();
        String discussion = thread.substring(thread.lastIndexOf('/') + 1);
        assertThat(gitlab.threadWrites()).containsExactly(discussion + " resolved=true");

        // Review 3: the problem is back. GitLab never reopens T1, so the same summary and a new note say it again.
        try (var review = claimed(work, "3".repeat(40), 10, QUOTE)) {
            var admitted = admitStaged(review, notMet(review));
            ActionTestSupport.drain(executor, jdbc);
            assertThat(gitlab.threadWrites()).containsExactly(discussion + " resolved=true");
            // The resolved note no longer stands as advice, so its summary words do not hold the new note back.
            assertThat(copies.findClosedAsFixed(work.workspaceId(), List.of(note.getId())))
                    .containsExactly(note.getId());
            // The composer reads it so: T1 is closed as fixed. Review 1's summary rests on the same practice, so
            // the piece still stands as that practice's advice and closes none of its practices.
            var history = admission.publicFeedbackHistory(review.identity()).history();
            assertThat(history.feedback())
                    .filteredOn(entry -> entry.id().equals(first))
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.closedPractices()).isEmpty();
                        assertThat(entry.copies())
                                .filteredOn(copy -> copy.id().equals(note.getId()))
                                .singleElement()
                                .satisfies(
                                        copy -> assertThat(copy.closedAsFixed()).isTrue());
                        assertThat(entry.copies())
                                .filteredOn(copy -> copy.kind() == FeedbackCopyKind.SUMMARY)
                                .singleElement()
                                .satisfies(
                                        copy -> assertThat(copy.closedAsFixed()).isFalse());
                    });

            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            admission.publishPackage(review.identity(), composition(admitted, context));
            var third = FeedbackIds.onWork(work.workspaceId(), review.reviewId(), work.developerId());
            assertThat(feedback.findByIdAndWorkspaceId(third, work.workspaceId()))
                    .hasValueSatisfying(row -> assertThat(row.getStopReason()).isNull());
            ActionTestSupport.drain(executor, jdbc);

            assertThat(feedback.findByIdAndWorkspaceId(third, work.workspaceId()))
                    .hasValueSatisfying(row -> assertThat(row.getState()).isEqualTo(FeedbackState.DELIVERED));
            var t2 = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(work.workspaceId(), third).stream()
                    .filter(copy -> copy.getKind() == FeedbackCopyKind.LINE_NOTE)
                    .findFirst()
                    .orElseThrow();
            assertThat(t2.getState()).isEqualTo(FeedbackCopyState.POSTED);
            assertThat(t2.getExternalRef()).isNotEqualTo(t1).startsWith("gid://gitlab/DiffNote/");
            assertThat(t2.getThreadRef()).isNotEqualTo(thread);
        }
        // T1 is untouched: still resolved, with its words, and no write ever asked GitLab to reopen it.
        assertThat(gitlab.resolved(t1)).isTrue();
        assertThat(body(mergeRequest, t1)).isEqualTo(t1Body);
        assertThat(gitlab.threadWrites()).containsExactly(discussion + " resolved=true");
    }

    private String body(String mergeRequest, String noteId) {
        return gitlab.threads().on(mergeRequest).stream()
                .filter(comment -> comment.id().equals(noteId))
                .findFirst()
                .orElseThrow()
                .body();
    }

    private UUID resolution(Work work, String thread) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM feedback_thread_resolution WHERE workspace_id = ? AND thread_ref = ?",
                UUID.class,
                work.workspaceId(),
                thread));
    }

    private record Work(
            ReviewIntegrationData.Scenario scenario,
            ItemRef item,
            long pullRequestId,
            int iid,
            long authorNativeId,
            long developerId,
            String practiceSlug) {
        long workspaceId() {
            return item.workspaceId();
        }
    }

    private record Claimed(
            ResultAdmissionService.AdmissionIdentity identity,
            UUID reviewId,
            String practiceSlug,
            long itemId,
            String itemVersion,
            String head,
            String text,
            PreparedJobInputs inputs)
            implements AutoCloseable {
        @Override
        public void close() {
            inputs.close();
        }
    }

    /** A merge request on a monitored GitLab project, through a connection to the scripted GitLab. */
    private Work mergeRequest() {
        var scenario = data.scenario(DefinitionTestFixtures.definition());
        var workspace = scenario.workspace();
        jdbc.update(
                "UPDATE practice SET feedback_mode=? WHERE workspace_id=? AND id=?",
                FeedbackMode.AUTOMATIC.name(),
                workspace.getId(),
                scenario.practice().getId());
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, ScriptedGitLabNotes.SERVER)
                .orElseGet(() ->
                        providers.save(new IdentityProvider(IdentityProviderType.GITLAB, ScriptedGitLabNotes.SERVER)));
        var random = ThreadLocalRandom.current();
        long authorNativeId = random.nextLong(1_000_000, Long.MAX_VALUE);
        var author = users.save(TestUserFactory.createUser(authorNativeId, "author-" + authorNativeId, provider));
        var identity = new Identity();
        identity.setAccount(scenario.account());
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey(Long.toString(authorNativeId));
        identities.saveAndFlush(identity);
        var connection = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                null,
                new ConnectionConfig.GitLabConfig(
                        ScriptedGitLabNotes.SERVER, random.nextLong(1, Long.MAX_VALUE), null, Set.of(), null));
        connection.setCredentials(new BearerToken("contract-token-" + authorNativeId, null), credentials);
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var now = Instant.now();
        var repository = new Repository();
        repository.setNativeId(random.nextLong(1, Long.MAX_VALUE));
        repository.setProvider(provider);
        repository.setName("work-" + authorNativeId);
        repository.setNameWithOwner("contract-group/work-" + authorNativeId);
        repository.setHtmlUrl(ScriptedGitLabNotes.SERVER + "/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setPushedAt(now);
        repository.setCreatedAt(now);
        repository.setUpdatedAt(now);
        repository = repositories.saveAndFlush(repository);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                workspace, repository.getNameWithOwner(), repository.getNativeId()));
        // The scripted GitLab keys its threads by the merge request's number, which the shared context shares.
        int iid = random.nextInt(1_000, Integer.MAX_VALUE);
        var pull = new PullRequest();
        pull.setNativeId(random.nextLong(1, Long.MAX_VALUE));
        pull.setProvider(provider);
        pull.setRepository(repository);
        pull.setNumber(iid);
        pull.setTitle("Retry the invoice export");
        pull.setBody("The export handler now catches the failure.");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(author);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/-/merge_requests/" + iid);
        pull.setCreatedAt(now);
        pull.setUpdatedAt(now);
        pull.setHeadRefOid("1".repeat(40));
        pull.setBaseRefOid(BASE);
        pull.setHeadRefName("export-retry");
        pull.setBaseRefName("main");
        pull = pullRequests.saveAndFlush(pull);
        var parent = new Item();
        parent.setWorkspaceId(workspace.getId());
        parent.setKind(ScmItemEvents.REPOSITORY);
        parent.setSourceId(repository.getId());
        parent.setCreatedAt(now);
        parent = items.saveAndFlush(parent);
        var pullItem = new Item();
        pullItem.setWorkspaceId(workspace.getId());
        pullItem.setKind(ScmItemEvents.PULL_REQUEST);
        pullItem.setParentId(parent.getId());
        pullItem.setSourceId(pull.getId());
        pullItem.setCreatedAt(now);
        pullItem = items.saveAndFlush(pullItem);
        return new Work(
                new ReviewIntegrationData.Scenario(workspace, scenario.account(), scenario.practice(), pullItem),
                new ItemRef(
                        workspace.getId(),
                        pullItem.getId(),
                        pullItem.getKind(),
                        pullItem.getSourceId(),
                        pullItem.getParentId(),
                        null),
                pull.getId(),
                iid,
                authorNativeId,
                Objects.requireNonNull(scenario.account().getId()),
                scenario.practice().getSlug());
    }

    /**
     * A review of the merge request pushed to {@code head}, whose changed line reads {@code line}: the attempt's capture
     * holds the change and the reviewed file whole, and GitLab holds the matching diff version.
     */
    private Claimed claimed(Work work, String head, int minutesAgo, String line) {
        jdbc.update("UPDATE issue SET head_ref_oid = ? WHERE id = ?", head, work.pullRequestId());
        var version = itemSource.currentVersion(work.item()).orElseThrow();
        var pending = admissions.pending(
                work.scenario(),
                Instant.now().minus(Duration.ofMinutes(minutesAgo)),
                work.developerId(),
                version.value());
        jdbc.update(
                "UPDATE review SET base_version=? WHERE workspace_id=? AND id=?",
                version.baseVersion(),
                work.workspaceId(),
                pending.review().getId());
        var job = jobs.findById(pending.jobId()).orElseThrow();
        String text = file("    " + line);
        var staged = new LinkedHashMap<String, byte[]>();
        staged.put(RECORD, "# Retry the invoice export\n".getBytes(StandardCharsets.UTF_8));
        staged.put(
                CHANGE,
                mapper.writeValueAsBytes(Map.of(
                        "base_sha",
                        BASE,
                        "head_sha",
                        head,
                        "diff",
                        "diff --git a/" + PATH + " b/" + PATH + "\n--- a/" + PATH + "\n+++ b/" + PATH + "\n"
                                + hunk(line))));
        staged.put("context/citable/new/" + PATH, text.getBytes(StandardCharsets.UTF_8));
        staged.put("context/citable/old/" + PATH, file("    export();").getBytes(StandardCharsets.UTF_8));
        var captured = capture(staged, head);
        staged.put("INDEX.json", mapper.writeValueAsBytes(captured.path("manifest")));
        files.beginPersonCapture(job);
        recorder.recordIdentity(new PersonCopyIdentity(
                "GITLAB", ScriptedGitLabNotes.SERVER, Long.toString(work.authorNativeId()), null));
        var inputs = files.prepare(job, new PreparedEvidence(Map.copyOf(staged), null), null);
        job.setCapture(captured);
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://model.example",
                                "contract-model",
                                null,
                                null,
                                null,
                                null,
                                DataHandlingTier.UNDECLARED,
                                null,
                                null,
                                false),
                        200_000,
                        4_000,
                        600)
                .toJson(mapper));
        jobs.saveAndFlush(job);
        gitlab.diffVersion(BASE, START, head, PATH, hunk(line));
        return new Claimed(
                new ResultAdmissionService.AdmissionIdentity(job.getId(), work.workspaceId(), 0, job.getRunId()),
                pending.review().getId(),
                work.practiceSlug(),
                work.item().itemId(),
                version.value(),
                head,
                text,
                inputs);
    }

    private static String file(String changed) {
        return "class Export {\n  void run() {\n" + changed + "\n  }\n}\n";
    }

    private static String hunk(String line) {
        return "@@ -1,5 +1,5 @@\n class Export {\n   void run() {\n-    export();\n+    " + line + "\n   }\n }\n";
    }

    /* The attempt's capture: the merge request record, and the change with both sides of the reviewed file. */
    private ObjectNode capture(Map<String, byte[]> staged, String head) {
        var capture = EvidenceSnapshotFixtures.snapshot(mapper);
        var core = EvidenceSnapshotFixtures.availableSource(capture, "scm.pull-request.core", null);
        EvidenceSnapshotFixtures.file(capture, core, RECORD, sha(Objects.requireNonNull(staged.get(RECORD))))
                .put("bytes", Objects.requireNonNull(staged.get(RECORD)).length);
        var change = EvidenceSnapshotFixtures.availableSource(capture, DIFF, BASE + ":" + head);
        EvidenceSnapshotFixtures.file(capture, change, CHANGE, sha(Objects.requireNonNull(staged.get(CHANGE))))
                .put("bytes", Objects.requireNonNull(staged.get(CHANGE)).length);
        var texts = capture.withObject("manifest").withArray("citableTexts");
        texts.addObject()
                .put("source", "scm.pull-request.core")
                .put("path", RECORD)
                .put("side", "NEW")
                .putNull("commit")
                .put("stagedPath", RECORD)
                .put("sha256", sha(Objects.requireNonNull(staged.get(RECORD))));
        for (var side : List.of("new", "old")) {
            String staging = "context/citable/" + side + "/" + PATH;
            texts.addObject()
                    .put("source", DIFF)
                    .put("path", PATH)
                    .put("side", side.toUpperCase(Locale.ROOT))
                    .put("commit", side.equals("new") ? head : BASE)
                    .put("stagedPath", staging)
                    .put("sha256", sha(Objects.requireNonNull(staged.get(staging))));
        }
        return capture;
    }

    private static String sha(byte[] bytes) {
        return ProvenanceDigest.sha256Hex(bytes);
    }

    /** A complete read of the reviewed file, which makes the review's coverage a proof of absence. */
    private ReadReceipt receipt(Claimed review) {
        return new ReadReceipt(
                new SourceKind(DIFF),
                CHANGE,
                PATH,
                review.head(),
                ReadReceiptStatus.COMPLETE,
                sha(review.text().getBytes(StandardCharsets.UTF_8)),
                null,
                null,
                null,
                null);
    }

    private SubmittedResultDTO notMet(Claimed review) {
        var candidate = mapper.readTree("""
            {"outcome":"NOT_MET","summary":"The export handler drops the failure cause",
             "explanation":"The merge request's export handler returns without the failure cause, so an operator cannot trace a failed export.",
             "citations":[],
             "problems":[{"key":"failure-cause-dropped","severity":"MAJOR","summary":"The failure cause is dropped",
               "explanation":"The new handler catches the export failure and returns without it.",
               "citations":[{"sourceKind":"%s","sourcePath":"%s","path":"%s","side":"NEW","commit":"%s",
                 "startLine":3,"endLine":3,"quote":"%s"}],
               "basis":{"type":"introduced","head":0,"explanation":"This merge request adds the handler."},
               "nextStep":"Rethrow the failure with its cause, or record the export as failed."}],
             "evidence":{}}
            """.formatted(DIFF, CHANGE, PATH, review.head(), QUOTE));
        return submitted(review, (ObjectNode) candidate);
    }

    private SubmittedResultDTO met(Claimed review) {
        var candidate = mapper.readTree("""
            {"outcome":"MET","summary":"The export handler keeps the failure cause",
             "explanation":"The merge request's export handler rethrows the failure with its cause.",
             "citations":[{"sourceKind":"%s","sourcePath":"%s","path":"%s","side":"NEW","commit":"%s",
               "startLine":3,"endLine":3,"quote":"%s"}],
             "evidence":{}}
            """.formatted(DIFF, CHANGE, PATH, review.head(), FIXED));
        return submitted(review, (ObjectNode) candidate);
    }

    private SubmittedResultDTO submitted(Claimed review, ObjectNode candidate) {
        var receipts = List.of(receipt(review));
        candidate
                .putObject("coverage")
                .put("source", "host")
                .put("reads", 1)
                .put("truncatedReads", 0)
                .put("failedReads", 0)
                .put("complete", true)
                .set("receipts", mapper.valueToTree(receipts));
        return new SubmittedResultDTO(
                review.practiceSlug(), Long.toString(review.itemId()), review.itemVersion(), candidate, receipts);
    }

    private AdmissionResponseDTO admitStaged(Claimed review, SubmittedResultDTO result) {
        var identity = review.identity();
        admission.stage(
                identity,
                new AdmissionRequestDTO(
                        3, review.reviewId(), identity.jobId(), identity.attempt(), List.of(result), List.of()));
        return admission.admit(
                identity, new SealResultsRequestDTO(3, review.reviewId(), identity.jobId(), identity.attempt()));
    }

    /* The same summary and one diff note on the admitted problem, composed from the named public history. */
    private static FeedbackPackage composition(AdmissionResponseDTO admitted, @Nullable String publicationContext) {
        var result = admitted.admitted().getFirst();
        var problem = result.problems().getFirst().id();
        var support = List.of(new FeedbackCompositionTrace.Support(result.resultId(), problem));
        return new FeedbackPackage(
                List.of(result.resultId()),
                List.of(new ComposedFeedback(
                        FeedbackDestination.ON_WORK,
                        List.of(result.resultId()),
                        SUMMARY,
                        List.of(new ComposedFeedback.Note(problem, 0, NOTE)),
                        null,
                        new FeedbackCompositionTrace(
                                List.of(
                                        new FeedbackCompositionTrace.CopySupport(FeedbackCopyKind.SUMMARY, 0, support),
                                        new FeedbackCompositionTrace.CopySupport(
                                                FeedbackCopyKind.LINE_NOTE, 1, support)),
                                List.of(),
                                null,
                                result.resultId()),
                        null)),
                true,
                publicationContext);
    }

    private void silence(boolean engaged) {
        var current = settings.get();
        if (current.isSilentModeEngaged() != engaged)
            settings.updateSilentMode(
                    engaged,
                    engaged ? "restored after the GitLab returning problem test" : null,
                    null,
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
    }
}
