package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.ContextManifestBuilder;
import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.curated.CatalogProvenanceBackfill;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.AdmittedReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Copies of the close-outcome practice installed before it shipped needing human review keep their old,
 * automated policy. Whatever that stored policy, and whichever provider the workspace uses, none of them
 * may be reviewed or have a claim admitted, while a workspace-authored practice with the same policy still
 * runs.
 */
class ClosedIssueOutcomeWithdrawalIntegrationTest extends BaseIntegrationTest {

    private static final String WITHDRAWN = "issue-closed-with-unmet-outcome";
    private static final SourceKind CORE = new SourceKind("scm.issue.core");
    private static final SourceKind COMMENTS = new SourceKind("scm.issue.comments");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private PracticeDetectionDeliveryService deliveryService;

    @Autowired
    private ContextManifestBuilder manifests;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private WorkspaceAgentBindingRepository workspaceAgentBindingRepository;

    @Autowired
    private LlmModelResolver llmModelResolver;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogProvenanceBackfill catalogRepair;

    @ParameterizedTest
    @EnumSource(Provider.class)
    void shouldSwitchOffInstalledCopiesWithoutTouchingTheirDefinitionOrAnUnrelatedPractice(Provider provider) {
        Installed installed = install(provider);
        Practice unrelated = installPractice(installed.workspace(), "closure-notes-are-kept", null);

        catalogRepair.run();

        Practice copy =
                practiceRepository.findById(installed.practice().getId()).orElseThrow();
        assertThat(copy.getAutonomy()).isEqualTo(PracticeAutonomy.OFF);
        assertThat(copy.getCriteria()).isEqualTo(installed.practice().getCriteria());
        assertThat(copy.getCurrentRevision().getId())
                .isEqualTo(installed.practice().getCurrentRevision().getId());
        assertThat(practiceRepository.findById(unrelated.getId()).orElseThrow().getAutonomy())
                .isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM config_audit_event WHERE workspace_id = ? "
                                + "AND entity_type = 'PRACTICE_USAGE' AND entity_id = ?",
                        Long.class,
                        installed.workspace().getId(),
                        String.valueOf(copy.getId())))
                .isEqualTo(1);
    }

    @Test
    void shouldLeaveAPracticeAuthoredUnderTheSameSlugReviewable() {
        Installed installed = install(Provider.GITLAB);
        Workspace other = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("close-authored"));
        Practice authored = installPractice(other, WITHDRAWN, null);

        catalogRepair.run();

        assertThat(practiceRepository.findById(authored.getId()).orElseThrow().getAutonomy())
                .isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
        assertThat(manifests
                        .checkAutomatedReviewReadinessAsOfNow(completeIssueCapture(installed), List.of(authored))
                        .readyPractices())
                .containsExactly(authored);
    }

    @Test
    void shouldFailTheRepairWhenAWithdrawnCopyCannotBeSwitchedOff() {
        Installed repairable = install(Provider.GITHUB);
        Workspace other = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("close-unreadable"));
        Practice unreadable = installPractice(other, WITHDRAWN, WITHDRAWN);
        jdbcTemplate.update(
                "UPDATE practice SET automated_review_policy = '{\"subject\":\"INVALID\"}'::jsonb WHERE id = ?",
                unreadable.getId());

        assertThatThrownBy(catalogRepair::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not switch off 1 practice(s)");
        assertThat(practiceRepository
                        .findById(repairable.practice().getId())
                        .orElseThrow()
                        .getAutonomy())
                .isEqualTo(PracticeAutonomy.OFF);
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void shouldNotReviewAnInstalledCopyWhoseStoredPolicyStillAutomatesTheCloseQuestion(Provider provider) {
        Installed installed = install(provider);
        Practice unrelated = installPractice(installed.workspace(), "closure-notes-are-kept", null);

        var readiness = manifests.checkAutomatedReviewReadinessAsOfNow(
                completeIssueCapture(installed), List.of(installed.practice(), unrelated));

        assertThat(readiness.readyPractices()).containsExactly(unrelated);
        assertThat(readiness.decisions())
                .filteredOn(decision -> decision.practiceSlug().equals(WITHDRAWN))
                .singleElement()
                .satisfies(decision -> assertThat(decision.reasonCodes())
                        .containsExactly(AutomatedReviewReadinessReason.DECLARED_EVIDENCE_INSUFFICIENT));
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void shouldAdmitNoClaimFromAReviewPreparedBeforeTheWithdrawal(Provider provider) {
        Installed installed = install(provider);
        AgentJob job = queuedCloseReview(installed);
        ValidatedObservation lapse = claim(Presence.PRESENT, Severity.MAJOR);
        ValidatedObservation cleanClose = claim(Presence.ABSENT, null);

        // Named, because an unverifiable quote is withheld through the same refusal.
        assertThatThrownBy(() -> deliveryService.prepare(job, List.of(lapse, cleanClose)))
                .isInstanceOf(ObservationsRefusedException.class)
                .hasMessageContaining("withheld=[" + WITHDRAWN + ": withdrawn from automated review, " + WITHDRAWN
                        + ": withdrawn from automated review]");

        for (String table : List.of("observation", "feedback", "feedback_dispatch")) {
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                            Long.class,
                            installed.workspace().getId()))
                    .as("%s rows in the %s workspace", table, installed.provider())
                    .isZero();
        }
    }

    enum Provider {
        GITHUB(IdentityProviderType.GITHUB, "https://github.com"),
        GITLAB(IdentityProviderType.GITLAB, "https://gitlab.com");

        private final IdentityProviderType type;
        private final String serverUrl;

        Provider(IdentityProviderType type, String serverUrl) {
            this.type = type;
            this.serverUrl = serverUrl;
        }
    }

    private record Installed(Provider provider, Workspace workspace, Issue issue, Practice practice) {}

    @BeforeEach
    void cleanDatabase() {
        databaseTestUtils.cleanDatabase();
    }

    private Installed install(Provider source) {
        IdentityProviderType type = source.type;
        String serverUrl = source.serverUrl;
        String suffix = type.name().toLowerCase(java.util.Locale.ROOT);
        Workspace workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("close-" + suffix));
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> identityProviderRepository.save(new IdentityProvider(type, serverUrl)));
        User author = userRepository.save(TestUserFactory.createUser(300L, "closer-" + suffix, provider));

        Repository repository = new Repository();
        repository.setNativeId(3001L);
        repository.setProvider(provider);
        repository.setName("project");
        repository.setNameWithOwner("org/project");
        repository.setHtmlUrl(serverUrl + "/org/project");
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);

        Instant closedAt = Instant.now();
        Issue issue = new Issue();
        issue.setNativeId(30001L);
        issue.setProvider(provider);
        issue.setNumber(7);
        issue.setTitle("Ship the export");
        issue.setBody("- [ ] Export to CSV\n- [x] Export to JSON");
        issue.setState(Issue.State.CLOSED);
        issue.setStateReason(Issue.StateReason.COMPLETED);
        issue.setClosedAt(closedAt);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/7");
        issue.setRepository(repository);
        issue.setAuthor(author);
        issue.setCreatedAt(closedAt.minusSeconds(3600));
        issue.setUpdatedAt(closedAt);
        issue = issueRepository.save(issue);

        return new Installed(source, workspace, issue, installPractice(workspace, WITHDRAWN, WITHDRAWN));
    }

    /** A copy as it stood before this release: automated, reading the current issue, switched on. */
    private Practice installPractice(Workspace workspace, String slug, @Nullable String sourceCuratedSlug) {
        Practice practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName("Confirm the outcome before closing the issue");
        practice.setCriteria("Judge the closure from the current checklist.");
        practice.setSourceCuratedSlug(sourceCuratedSlug);
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.ISSUE));
        practice.setBindings(List.of(PracticeBinding.on(
                ScmSignals.ISSUE_CLOSED,
                List.of(
                        new PracticeEvidenceRequirement(CORE, EvidenceStance.REQUIRED),
                        new PracticeEvidenceRequirement(COMMENTS, EvidenceStance.EXHAUSTIVE)))));
        practice.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        return practiceRepository.saveAndFlush(practice);
    }

    private de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest completeIssueCapture(Installed installed) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("inputs/context/metadata.json", "{}".getBytes(StandardCharsets.UTF_8));
        files.put("inputs/context/comments.json", "[]".getBytes(StandardCharsets.UTF_8));
        return manifests.augment(
                files,
                Map.of("inputs/context/metadata.json", CORE, "inputs/context/comments.json", COMMENTS),
                "close-" + installed.issue().getId(),
                new EvidencePlan(new SourceContractVersion("1.2.0"), ArtifactKinds.ISSUE),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(CORE, SourceCompleteness.COMPLETE, COMMENTS, SourceCompleteness.COMPLETE),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(CORE, COMMENTS)));
    }

    /** A close review that admitted the copy and ran before this release, now returning its claims. */
    private AgentJob queuedCloseReview(Installed installed) {
        AgentJob job = new AgentJob();
        job.setWorkspace(installed.workspace());
        job.setWorkerId("test-worker");
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        job.setConfigSnapshot(AdmittedReviewJobFixtures.snapshot(
                installed.workspace(),
                llmConnectionRepository,
                llmModelRepository,
                workspaceAgentBindingRepository,
                llmModelResolver,
                OBJECT_MAPPER));
        ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
        metadata.put("artifact_kind", ArtifactKinds.ISSUE.value());
        metadata.put("issue_id", installed.issue().getId());
        Repository repository = Objects.requireNonNull(installed.issue().getRepository());
        metadata.put("repository_id", repository.getId());
        metadata.put("repository_full_name", repository.getNameWithOwner());
        metadata.put("issue_number", installed.issue().getNumber());
        metadata.put(PracticeCatalogInjector.SIGNAL_METADATA_KEY, ScmSignals.ISSUE_CLOSED.value());
        job.setMetadata(metadata);
        ObjectNode snapshot = EvidenceSnapshotFixtures.snapshot(OBJECT_MAPPER, ArtifactKinds.ISSUE.value());
        EvidenceSnapshotFixtures.artifact(
                EvidenceSnapshotFixtures.availableSource(snapshot, CORE.value(), null),
                "inputs/context/description.md",
                "0".repeat(64));
        EvidenceSnapshotFixtures.artifact(
                EvidenceSnapshotFixtures.availableSource(snapshot, COMMENTS.value(), null),
                "inputs/context/comments.json",
                "0".repeat(64));
        EvidenceSnapshotFixtures.admittedPractice(
                snapshot,
                WITHDRAWN,
                Objects.requireNonNull(installed.practice().getCurrentRevision().getId()));
        job.setEvidenceSnapshot(snapshot);
        return agentJobRepository.save(job);
    }

    private static ValidatedObservation claim(Presence presence, @Nullable Severity severity) {
        ObjectNode evidence = OBJECT_MAPPER.createObjectNode();
        evidence.putArray("citations")
                .addObject()
                .put("sourceKind", CORE.value())
                .put("artifactPath", "inputs/context/description.md")
                .put("quote", "- [ ] Export to CSV");
        return new ValidatedObservation(
                WITHDRAWN,
                "Closed with an unchecked item",
                AssessmentStatus.ASSESSED,
                presence,
                Assessment.BAD,
                severity,
                evidence,
                null);
    }
}
