package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.account.UserPreferences;
import de.tum.cit.aet.hephaestus.account.UserPreferencesRepository;
import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobExecutor;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateNonceStore;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessage;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDraft;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PersonDataErasureIntegrationTest extends BaseIntegrationTest {
    @TempDir
    private Path evidenceRoot;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private PersonDataCopyFence copyFence;

    @Autowired
    private ObjectProvider<AgentJobLifecycleService> lifecycles;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private PersonProcessingSuppression suppression;

    @Autowired
    private MentorAdmission mentor;

    @Autowired
    private ObservationInvalidationRepository invalidations;

    @Autowired
    private PersonDataRegistry registry;

    @Autowired
    private PersonIdentityResolver resolver;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OAuthStateNonceStore oauthNonces;

    @Autowired
    private IssuedJwtRepository issuedTokens;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private IdentityLinkRepository links;

    @Autowired
    private UserRepository users;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceMembershipRepository memberships;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private UserPreferencesRepository preferences;

    @Autowired
    private SlackMessageRepository slackMessages;

    @Autowired
    private SlackThreadRepository slackThreads;

    @Autowired
    private MentorConversationService mentorConversations;

    @Autowired
    private MentorEntryService mentorEntries;

    @Autowired
    private MentorEntryRepository mentorEntryRepository;

    @Autowired
    private MentorTimeline mentorTimeline;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private FeedbackPlacementRepository feedbackPlacementRepository;

    private static final Map<String, String> RETAINED_AFTER_ERASURE = Map.of(
            "account", "the deleted-account tombstone",
            "user", "the anonymised provider profile other people's work still references",
            "person_suppression", "the permanent native-identity processing control",
            "person_data_request", "the re-resolution preview request itself",
            "agent_job_evidence_copy", "frozen job ids still name the redacted job rows",
            "slack_thread", "frozen thread ids still name the shared thread without the person",
            "mentor_entry_tool_result_copy", "frozen thread and job ids still name the workspaces, not any entry");

    @BeforeEach
    void clearRows() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldIncludeLegacyProviderCommentReferencesAndRejectTheirChangedInspection() {
        var fixture = externalInspectionFixture("https://privacy-gitlab.example.com/team/repo/-/merge_requests/19");
        jdbc.update("UPDATE agent_job SET delivery_comment_id='legacy-11' WHERE id=?", fixture.jobId());
        var preview = personData.preview(
                fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null)));
        assertThat(preview.externalDeliveries())
                .containsExactly(new PersonDataContributor.ExternalDelivery(
                        fixture.workspaceId(), "https://privacy-gitlab.example.com/team/repo/-/merge_requests/19"));
        assertThat(personData
                        .export(preview.request().getId())
                        .path("stores")
                        .path("agent_job")
                        .get(0)
                        .path("delivery_comment_id")
                        .asString())
                .isEqualTo("legacy-11");
        assertThatThrownBy(() -> personData.requestErasure(preview.request().getId(), fixture.administratorId(), false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("un-deliver runbook");
        jdbc.update("UPDATE agent_job SET delivery_comment_id='legacy-12' WHERE id=?", fixture.jobId());
        assertThatThrownBy(() -> personData.requestErasure(preview.request().getId(), fixture.administratorId(), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("preview scope changed");
    }

    @Test
    void shouldKeepLegacyProviderFeedbackWhenItsExactInspectionLocatorIsMissing() {
        var fixture = externalInspectionFixture(null);
        jdbc.update("UPDATE agent_job SET delivery_comment_id='legacy-11' WHERE id=?", fixture.jobId());
        assertThatThrownBy(() -> personData.preview(
                        fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(fixture.jobId().toString())
                .hasMessageContaining("no exact locator for the reviewed-work inspection");
        assertThat(jdbc.queryForObject(
                        "SELECT delivery_comment_id FROM agent_job WHERE id=?", String.class, fixture.jobId()))
                .isEqualTo("legacy-11");
    }

    @Test
    void shouldSuppressEverySelectedReviewDuringAdmissionAndRevokeItsHashOnCompletion() {
        var fixture = externalInspectionFixture("https://privacy-gitlab.example.com/team/repo/-/merge_requests/19");
        var preview = personData.preview(
                fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null)));
        personData.requestErasure(preview.request().getId(), fixture.administratorId(), true);
        assertThat(suppression.isReviewJobSuppressed(fixture.jobId())).isTrue();
        assertThat(suppression.isReviewJobSuppressed(UUID.randomUUID())).isFalse();
        personData.run(preview.request().getId());
        assertThat(personData.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(suppression.isReviewJobSuppressed(fixture.jobId())).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT job_token_hash FROM agent_job WHERE id=?", String.class, fixture.jobId()))
                .isNull();
    }

    @Test
    void shouldShowTheExactReviewedWorkLocatorWhenProviderWritesAreUnconfirmed() {
        var fixture = externalInspectionFixture("https://privacy-gitlab.example.com/team/repo/-/merge_requests/19");
        jdbc.update("""
                UPDATE feedback_dispatch SET state='UNCERTAIN',write_started=TRUE,
                    inline_write_started=TRUE,write_started_at=CURRENT_TIMESTAMP WHERE id=?
                """, fixture.dispatchId());
        var preview = personData.preview(
                fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null)));
        assertThat(preview.externalDeliveries())
                .containsExactly(new PersonDataContributor.ExternalDelivery(
                        fixture.workspaceId(), "https://privacy-gitlab.example.com/team/repo/-/merge_requests/19"));
        var exported =
                personData.export(preview.request().getId()).path("stores").path("feedback_dispatch");
        assertThat(exported.get(0).path("state").asString()).isEqualTo("UNCERTAIN");
        assertThat(exported.get(0).path("write_started").asBoolean()).isTrue();
        assertThat(exported.get(0).path("inline_write_started").asBoolean()).isTrue();
        assertThatThrownBy(() -> personData.requestErasure(preview.request().getId(), fixture.administratorId(), false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("un-deliver runbook");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM feedback_dispatch WHERE id=?", Long.class, fixture.dispatchId()))
                .isEqualTo(1);
    }

    @Test
    void shouldListAFencedInlineReceiptsKnownNoteAndTheReviewedWorkForItsUnknownOne() {
        var fixture = externalInspectionFixture("https://privacy-gitlab.example.com/team/repo/-/merge_requests/19");
        jdbc.update("""
                UPDATE feedback_dispatch SET state='UNCERTAIN',inline_write_started=TRUE,
                    write_started_at=CURRENT_TIMESTAMP,delivered_placements=CAST(? AS jsonb) WHERE id=?
                """, """
                [{"deliveryKey":"observation:a:0","path":"src/A.java","startLine":10,"disposition":"POSTED",
                  "externalRef":"gid://gitlab/Note/7",
                  "externalUrl":"https://privacy-gitlab.example.com/team/repo/-/merge_requests/19#note_7",
                  "writeMayHaveStarted":true},
                 {"deliveryKey":"observation:b:0","path":"src/B.java","startLine":20,"disposition":"FAILED",
                  "writeMayHaveStarted":true}]
                """, fixture.dispatchId());

        var preview = personData.preview(
                fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null)));

        assertThat(preview.externalDeliveries())
                .containsExactlyInAnyOrder(
                        new PersonDataContributor.ExternalDelivery(
                                fixture.workspaceId(),
                                "https://privacy-gitlab.example.com/team/repo/-/merge_requests/19#note_7"),
                        new PersonDataContributor.ExternalDelivery(
                                fixture.workspaceId(),
                                "https://privacy-gitlab.example.com/team/repo/-/merge_requests/19"));
    }

    @Test
    void shouldRejectThePreviewWhenAProviderWriteChangesOnTheSamePrimaryKey() {
        var fixture = externalInspectionFixture("https://privacy-gitlab.example.com/team/repo/-/merge_requests/19");
        var preview = personData.preview(
                fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null)));
        // The fixture holds projection ownership while preview and export inspect its provider write.
        jdbc.update("""
                UPDATE feedback_dispatch SET state='SENT',write_started=TRUE,
                    write_started_at=CURRENT_TIMESTAMP,delivered_external_ref='comment-99',
                    projection_owner='privacy-preview-fixture',projection_expires_at=CURRENT_TIMESTAMP + INTERVAL '1 day',
                    delivered_external_url='https://privacy-gitlab.example.com/team/repo/-/merge_requests/19#note_99'
                WHERE id=?
                """, fixture.dispatchId());
        assertThatThrownBy(() -> personData.export(preview.request().getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("preview scope changed");
        assertThatThrownBy(() -> personData.requestErasure(preview.request().getId(), fixture.administratorId(), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("preview scope changed");
        assertThat(personData.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.PREVIEW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM person_suppression", Long.class))
                .isZero();
        var refreshed = personData.preview(
                fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null)));
        assertThat(refreshed.externalDeliveries())
                .contains(new PersonDataContributor.ExternalDelivery(
                        fixture.workspaceId(),
                        "https://privacy-gitlab.example.com/team/repo/-/merge_requests/19#note_99"));
        assertThat(personData
                        .export(refreshed.request().getId())
                        .path("stores")
                        .path("feedback_dispatch")
                        .get(0)
                        .path("delivered_external_url")
                        .asString())
                .endsWith("#note_99");
    }

    @Test
    void shouldKeepTheProviderWriteWhenItsExactInspectionLocatorIsMissing() {
        var fixture = externalInspectionFixture(null);
        jdbc.update(
                "UPDATE feedback_dispatch SET state='UNCERTAIN',inline_write_started=NULL,package_content='{}'::jsonb WHERE id=?",
                fixture.dispatchId());
        assertThatThrownBy(() -> personData.preview(
                        fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(fixture.dispatchId().toString())
                .hasMessageContaining("needs inspection, but Hephaestus has no exact locator for the reviewed work");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM feedback_dispatch WHERE id=?", Long.class, fixture.dispatchId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM person_data_request", Long.class))
                .isZero();
    }

    private void keepCompositionInputs(DerivedConversation derived) {
        jdbc.update("""
                INSERT INTO review_composition_basis(job_id,workspace_id,admission_attempt,admission_digest,
                    basis_version,basis_digest,copy_id,byte_size,created_at,expires_at,payload)
                VALUES (?,?,0,?,1,?,?,32,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour',
                    '{"files":{"record":"ORIGINAL-BASIS-CANARY"}}'::jsonb)
                """, derived.jobId(), derived.workspaceId(), "a".repeat(64), "b".repeat(64), UUID.randomUUID());
    }

    private ExternalInspectionFixture externalInspectionFixture(@Nullable String reviewedWorkUrl) {
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://privacy-gitlab.example.com"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "display-only", provider));
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("external-inspection"));
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setArtifactKind(ArtifactKinds.PULL_REQUEST);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setConfigSnapshot(mapper.createObjectNode());
        var metadata = mapper.createObjectNode().put("author_id", target.getId());
        if (reviewedWorkUrl != null) metadata.put("pr_url", reviewedWorkUrl);
        job.setMetadata(metadata);
        job = agentJobRepository.saveAndFlush(job);
        UUID dispatchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO feedback_dispatch(id,destination_key,workspace_id,agent_job_id,destination,state,body,
                    practice_slugs,package_content,delivered_placements,write_started,inline_write_started,
                    next_attempt_at,attempt_count,created_at,updated_at)
                VALUES (?,?,?,?,'AUTOMATIC_REVIEW_PACKAGE','PENDING','Prepared feedback','[]'::jsonb,
                    '{"diffNotes":[]}'::jsonb,'[]'::jsonb,FALSE,FALSE,CURRENT_TIMESTAMP + INTERVAL '1 day',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, dispatchId, "review:" + job.getId(), workspace.getId(), job.getId());
        return new ExternalInspectionFixture(
                Objects.requireNonNull(administrator.getId()),
                Objects.requireNonNull(provider.getId()),
                Objects.requireNonNull(workspace.getId()),
                job.getId(),
                dispatchId);
    }

    private record ExternalInspectionFixture(
            long administratorId, long providerId, long workspaceId, UUID jobId, UUID dispatchId) {}

    @Test
    void erasesTargetAcrossTwoWorkspacesAndEveryRegisteredStoreWithoutTouchingAnotherPerson() throws Exception {
        IdentityProvider scm = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://privacy-gitlab.example.com"));
        IdentityProvider slack =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com"));
        Account administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        Account targetAccount = accounts.saveAndFlush(new Account("Target"));
        Account otherAccount = accounts.saveAndFlush(new Account("Other"));
        var targetOAuthBinding = new OAuthStateService.StateBinding(
                1L, IntegrationKind.GITHUB, Instant.now().truncatedTo(ChronoUnit.SECONDS), targetAccount.getId());
        oauthNonces.issue(
                "nonce-credential-canary",
                1L,
                IntegrationKind.GITHUB,
                targetOAuthBinding.issuedAt(),
                targetOAuthBinding.actorAccountId());
        UUID targetTokenId = UUID.randomUUID();
        issuedTokens.saveAndFlush(new IssuedJwt(
                targetTokenId,
                Objects.requireNonNull(targetAccount.getId()),
                Instant.now().plusSeconds(3600)));
        UUID targetSession = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();
        for (var entry :
                Map.of(targetSession, targetAccount, otherSession, otherAccount).entrySet()) {
            jdbc.update("""
                    INSERT INTO client_session(id,account_id,client_kind,client_id,session_expires_at,auth_time,created_at)
                    VALUES (?,?,'BROWSER_EXTENSION','privacy-client',CURRENT_TIMESTAMP+INTERVAL '1 hour',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, entry.getKey(), entry.getValue().getId());
            jdbc.update("""
                    INSERT INTO client_sign_in_handoff(code_hash,account_id,client_kind,client_id,redirect_uri,
                        code_challenge,session_expires_at,auth_time,expires_at,created_at)
                    VALUES (?,?,'BROWSER_EXTENSION','privacy-client','https://client.example.test/callback',
                        'pkce-credential-canary',CURRENT_TIMESTAMP+INTERVAL '1 hour',CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP+INTERVAL '60 seconds',CURRENT_TIMESTAMP)
                    """, entry.getKey().toString(), entry.getValue().getId());
        }
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", scm));
        User other = users.saveAndFlush(TestUserFactory.createUser(84L, "other", scm));
        link(targetAccount, scm, "42", null, target.getId());
        link(otherAccount, scm, "84", null, other.getId());
        preference(target);
        preference(other);
        var recorder = new ExactPersonDataCopyRecorder(jdbc);
        var worker = new EvidenceFolderPersonDataCatalog(
                new FabricLayout(evidenceRoot.toString()),
                jdbc,
                namedJdbc,
                mapper,
                recorder,
                copyFence,
                new DefaultListableBeanFactory().getBeanProvider(AgentJobExecutor.class),
                lifecycles);
        List<Path> targetFolders = new ArrayList<>();
        List<Path> otherFolders = new ArrayList<>();
        List<DerivedConversation> targetDerived = new ArrayList<>();
        List<DerivedConversation> otherDerived = new ArrayList<>();
        List<ConversationFeedbackCopy> copiedFeedback = new ArrayList<>();
        List<Long> sharedThreads = new ArrayList<>();
        for (int index = 1; index <= 2; index++) {
            String team = "T" + index;
            Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("privacy-" + index));
            connections.saveAndFlush(new Connection(
                    workspace,
                    IntegrationKind.SLACK,
                    team,
                    new ConnectionConfig.SlackConfig(team, null, null, Set.of())));
            link(targetAccount, slack, "UTARGET", team, null);
            link(otherAccount, slack, "UOTHER", team, null);
            membership(workspace, target);
            membership(workspace, other);
            jdbc.update(
                    """
                    INSERT INTO config_audit_event(occurred_at,workspace_id,actor_kind,actor_account_id,
                        acting_account_id,entity_type,entity_id,action,changed_keys)
                    VALUES (CURRENT_TIMESTAMP,?,'IMPERSONATED',?,?,'WORKSPACE_VISIBILITY',?,'UPDATED',ARRAY['visibility'])
                    """,
                    workspace.getId(),
                    otherAccount.getId(),
                    targetAccount.getId(),
                    workspace.getId().toString());
            jdbc.update(
                    """
                    INSERT INTO config_audit_event(occurred_at,workspace_id,actor_kind,actor_account_id,
                        entity_type,entity_id,action,changed_keys,old_value,new_value)
                    VALUES (CURRENT_TIMESTAMP,?,'USER',?,'WORKSPACE_ROLE',?,'UPDATED',ARRAY['role'],
                        '{"role":"MEMBER","hidden":false}'::jsonb,'{"role":"ADMIN","hidden":false}'::jsonb)
                    """,
                    workspace.getId(),
                    administrator.getId(),
                    target.getId().toString());
            SlackThread shared = slackThread(workspace, "100.1", target, other);
            sharedThreads.add(shared.getId());
            message(workspace, team, "100.1", "100.1", "UTARGET", target, "Target's collected work");
            message(workspace, team, "100.1", "200.1", "UOTHER", other, "Other person's shared reply");
            SlackThread unrelated = slackThread(workspace, "300.1", other);
            message(workspace, team, "300.1", "300.1", "UOTHER", other, "Unrelated conversation");
            targetDerived.add(seedDerivedConversation(workspace, shared.getId(), target));
            otherDerived.add(seedDerivedConversation(workspace, unrelated.getId(), other));
            copiedFeedback.add(seedFeedbackCopy(targetDerived.getLast(), otherDerived.getLast()));
            targetFolders.add(captureMountedEvidence(worker, recorder, targetDerived.getLast(), target));
            otherFolders.add(captureMountedEvidence(worker, recorder, otherDerived.getLast(), other));
        }
        for (var derived : targetDerived) {
            invalidations.saveAndFlush(new ObservationInvalidation(
                    observationRepository
                            .findById(derived.observationIds().getFirst())
                            .orElseThrow(),
                    Objects.requireNonNull(administrator.getId()),
                    "Correction before access request",
                    Instant.now()));
        }
        PersonScope otherScope = resolver.resolve(Objects.requireNonNull(otherAccount.getId()), List.of());
        // A shared source row, such as a thread both people joined, changes when the target's part is
        // erased. Compare the rows that belong to the other person alone.
        PersonScope otherPrimaryScope =
                new PersonScope(otherScope.accountId(), otherScope.identities(), otherScope.userIds());
        var otherSelection = registry.select(otherPrimaryScope);
        Map<String, Object> otherRows = new TreeMap<>();
        for (var contributor : registry.stores())
            otherRows.put(
                    contributor.store(),
                    withoutErasedCopies(
                            contributor.store(),
                            contributor.export(Objects.requireNonNull(otherSelection.get(contributor.store()))),
                            copiedFeedback));
        long administratorId = Objects.requireNonNull(administrator.getId());
        var preview = personData.preview(administratorId, targetAccount.getId(), List.of());
        UUID requestId = preview.request().getId();
        PersonScope frozen =
                mapper.readValue(Objects.requireNonNull(preview.request().getScopeJson()), PersonScope.class);
        var export = personData.export(requestId);
        Map<String, Long> counts = mapper.readValue(preview.request().getCountsJson(), new TypeReference<>() {});
        assertThat(counts.get("oauth_state_nonce")).isEqualTo(1L);
        assertThat(counts.get("client_session")).isEqualTo(1L);
        assertThat(counts.get("client_sign_in_handoff")).isEqualTo(1L);
        assertThat(export.path("stores").path("client_sign_in_handoff").toString())
                .doesNotContain(targetSession.toString(), "code_hash", "code_challenge");

        assertThat(counts.get("person_evidence_copy")).isEqualTo(2L);
        assertThat(export.path("stores").path("agent_job")).hasSize(2);
        for (var row : export.path("stores").path("agent_job")) {
            assertThat(row.path("evidence_snapshot").path("manifest").path("sources"))
                    .hasSize(1);
            assertThat(row.path("evidence_snapshot").path("manifest").path("artifacts"))
                    .hasSize(1);
        }
        assertThat(export.toString())
                .doesNotContain("MOUNTED-PROFILE-CANARY", "credential-canary", "unrelated-profile-canary");
        targetFolders.forEach(path -> assertThat(path).exists());
        otherFolders.forEach(path -> assertThat(path).exists());
        assertThat(counts.get("feedback")).isEqualTo(6L);
        assertThat(preview.externalDeliveries()).hasSize(2);
        assertThat(preview.externalDeliveries())
                .allSatisfy(delivery -> assertThat(delivery.locator()).startsWith("https://team-"));
        assertThat(export.path("stores").path("feedback_placement").toString())
                .contains("posted_comment_url", "https://team-");
        assertThatThrownBy(() -> personData.requestErasure(requestId, administratorId, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("un-deliver runbook");
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.PREVIEW);
        assertThat(counts.get("observation")).isEqualTo(4L);
        assertThat(counts.get("observation_invalidation")).isEqualTo(2L);
        assertThat(counts.get("config_audit_event_membership_subject")).isEqualTo(2L);
        assertThat(counts.get("mentor_conversation")).isEqualTo(2L);
        assertThat(counts.get("mentor_entry")).isEqualTo(2L);
        assertThat(counts.get("mentor_entry_feedback_copy")).isEqualTo(2L);
        assertThat(counts.get("mentor_entry_tool_result_copy")).isEqualTo(2L);
        assertThat(export.path("stores").path("mentor_entry_feedback_copy").toString())
                .contains("Target guidance copied into another conversation")
                .doesNotContain("Other reply to erased feedback", "user_id", "credential-canary");
        assertThat(counts.get("slack_message")).isEqualTo(2L);
        counts.forEach((store, count) -> assertThat(
                        (long) export.path("stores").path(store).size())
                .as("Frozen preview/export parity for %s", store)
                // Tool results in another member's conversation are redacted, never exported.
                .isEqualTo(store.equals("mentor_entry_tool_result_copy") ? 0L : count));
        assertThat(export.path("stores").path("mentor_entry"))
                .allSatisfy(row -> assertThat(row.path("text").asString()).isEqualTo("Delivered guidance"));
        assertThat(export.path("stores").path("mentor_entry").toString())
                .doesNotContain("payload", "runtime-credential-canary");
        assertThat(export.toString())
                .doesNotContain(
                        "Unrelated conversation",
                        "Other person's shared reply",
                        "credential-canary",
                        "unrelated-profile-canary");
        personData.requestErasure(requestId, administratorId, true);
        assertThat(oauthNonces.tryConsume("nonce-credential-canary", targetOAuthBinding))
                .isFalse();
        assertThat(issuedTokens.findActive(targetTokenId, Instant.now())).isEmpty();
        assertThat(issuedTokens.findById(targetTokenId).orElseThrow().getRevokedReason())
                .isEqualTo(IssuedJwt.RevokedReason.ACCOUNT_DELETED);
        assertThat(suppression.isUserSuppressed(target.getId())).isTrue();
        assertThat(suppression.isUserSuppressed(other.getId())).isFalse();
        for (var derived : targetDerived) {
            assertThat(mentor.refusal(derived.workspaceId(), target.getId())).contains(MentorRefusal.PERSON_ERASED);
            assertThat(mentor.refusal(derived.workspaceId(), other.getId()))
                    .isNotEqualTo(Optional.of(MentorRefusal.PERSON_ERASED));
        }
        for (long threadId : sharedThreads) {
            long workspaceId = Objects.requireNonNull(
                    jdbc.queryForObject("SELECT workspace_id FROM slack_thread WHERE id=?", Long.class, threadId));
            assertThat(suppression.isArtifactSuppressed(workspaceId, "chat.conversation_thread", threadId))
                    .isTrue();
            assertThat(suppression.isArtifactSuppressed(-1L, "chat.conversation_thread", threadId))
                    .isFalse();
        }
        try (var executor = Executors.newSingleThreadExecutor()) {
            var erasure = executor.submit(() -> personData.run(requestId));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!erasure.isDone()) {
                worker.removeLocalRequests();
                if (System.nanoTime() > deadline) throw new AssertionError("Mounted worker did not complete erasure");
                Thread.sleep(20);
            }
            erasure.get(10, TimeUnit.SECONDS);
        }
        targetFolders.forEach(path -> assertThat(path).doesNotExist());
        otherFolders.forEach(path -> assertThat(path).exists());
        for (var derived : targetDerived) {
            var job = agentJobRepository.findById(derived.jobId()).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AgentJobStatus.CANCELLED);
            assertThat(job.getEvidenceSnapshot()).isNull();
        }

        var receipt = personData.get(requestId).request();
        assertThat(receipt.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(receipt.getScopeJson()).isNull();
        assertThat(receipt.getSelectionsJson()).isNull();
        Map<String, Long> completed = PersonDataStoreReceipt.counts(mapper, receipt.getCompletedJson());
        Map<String, Long> differentReceipts = new TreeMap<>();
        completed.forEach((store, count) -> {
            if (!count.equals(counts.get(store))) differentReceipts.put(store, count);
        });
        assertThat(completed.keySet()).containsExactlyInAnyOrderElementsOf(counts.keySet());
        assertThat(differentReceipts)
                .as("Receipt counts that differ from the preview")
                .isEmpty();
        // Re-resolve the person as a new request would, and replay the frozen closure: only a store that
        // deliberately keeps an anonymised, tombstone or control row may still select one.
        var again = personData.preview(administratorId, targetAccount.getId(), frozen.identities());
        Map<String, Long> reselected = mapper.readValue(again.request().getCountsJson(), new TypeReference<>() {});
        var frozenAfter = registry.select(frozen);
        Map<String, List<Long>> remaining = new TreeMap<>();
        for (var store : registry.stores()) {
            long resolvedRows = Objects.requireNonNull(reselected.get(store.store()));
            long frozenRows = Objects.requireNonNull(frozenAfter.get(store.store()))
                    .rows()
                    .size();
            if (resolvedRows != 0 || frozenRows != 0) remaining.put(store.store(), List.of(resolvedRows, frozenRows));
        }
        assertThat(remaining)
                .as("Rows still selected after erasure: [re-resolved, frozen]")
                .containsOnlyKeys(RETAINED_AFTER_ERASURE.keySet());
        assertThat(accounts.findById(Objects.requireNonNull(targetAccount.getId()))
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(Account.Status.DELETED);
        assertThat(users.findById(target.getId()).orElseThrow().getLogin()).startsWith("erased-");
        for (var derived : targetDerived) {
            assertThat(mentor.refusal(derived.workspaceId(), target.getId())).contains(MentorRefusal.PERSON_ERASED);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM config_audit_event WHERE actor_account_id=? AND acting_account_id IS NULL",
                        Long.class,
                        otherAccount.getId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM config_audit_event WHERE entity_type='WORKSPACE_ROLE' AND entity_id='ERASED' "
                                + "AND old_value->>'role'='MEMBER' AND new_value->>'role'='ADMIN' AND actor_account_id=?",
                        Long.class,
                        administrator.getId()))
                .isEqualTo(2L);
        for (long threadId : sharedThreads) {
            assertThat(jdbc.queryForObject(
                            "SELECT message_count FROM slack_thread WHERE id=?", Integer.class, threadId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT cardinality(participant_member_ids) FROM slack_thread WHERE id=?",
                            Integer.class,
                            threadId))
                    .isEqualTo(1);
        }
        for (var copy : copiedFeedback) {
            MentorEntry erased =
                    mentorEntryRepository.findById(copy.messageId()).orElseThrow();
            assertThat(erased.isRedacted()).isTrue();
            assertThat(erased.getText()).isEmpty();
            assertThat(String.valueOf(erased.getPayload())).doesNotContain("Target guidance");
            assertThat(mentorEntryRepository
                            .findById(copy.replyId())
                            .orElseThrow()
                            .getText())
                    .isEqualTo("Other reply to erased feedback");
        }
        for (var contributor : registry.stores()) {
            // A shared thread's aggregate changes; the other person's participant projection does not.
            assertThat(withoutErasedCopies(
                            contributor.store(),
                            contributor.export(Objects.requireNonNull(otherSelection.get(contributor.store()))),
                            copiedFeedback))
                    .as("Another person's %s rows", contributor.store())
                    .isEqualTo(otherRows.get(contributor.store()));
        }
        personData.requestErasure(requestId, administratorId, true);
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getCompletedJson()).isEqualTo(receipt.getCompletedJson());
    }

    @Test
    void shouldStopWhenAnExactFeedbackCopyReferencesAnotherWorkspace() {
        IdentityProvider scm = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://copy-conflict.example.org"));
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", scm));
        User other = users.saveAndFlush(TestUserFactory.createUser(84L, "other", scm));
        Workspace first = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("copy-first"));
        Workspace second = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("copy-second"));
        var targetData = seedDerivedConversation(
                first, slackThread(first, "100.1", target).getId(), target);
        var otherData = seedDerivedConversation(
                second, slackThread(second, "200.1", other).getId(), other);
        var copied = seedFeedbackCopy(targetData, otherData);
        assertThatThrownBy(() -> registry.select(new PersonScope(
                        null,
                        List.of(new PersonIdentity(Objects.requireNonNull(scm.getId()), "42", null)),
                        List.of(target.getId()))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different workspace")
                .hasMessageContaining("exact delivery reference");
        assertThat(mentorEntryRepository
                        .findById(copied.messageId())
                        .orElseThrow()
                        .getText())
                .contains("Target guidance copied");
    }

    @Test
    void shouldRequireExactProviderKeysInsteadOfUsingSlackActorCaches() {
        IdentityProvider scm = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://cache-gitlab.example.org"));
        IdentityProvider slack =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com"));
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "cached-person", scm));
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("cached-person"));
        message(workspace, "TCACHE", "100.1", "100.1", "UCACHE", target, "Captured contribution");
        PersonIdentity gitlabKey = new PersonIdentity(Objects.requireNonNull(scm.getId()), "42", null);
        PersonIdentity slackKey = new PersonIdentity(Objects.requireNonNull(slack.getId()), "UCACHE", "TCACHE");
        assertThatThrownBy(() -> resolver.resolve(null, List.of(gitlabKey)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exact Slack identity");
        assertThatThrownBy(() -> resolver.resolve(null, List.of(slackKey)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exact SCM provider identity");
        assertThat(resolver.resolve(null, List.of(gitlabKey, slackKey)).userIds())
                .containsExactly(target.getId());
    }

    @Test
    void shouldRejectErasureAuthorizedByAnAdministratorWhoseRoleWasRevokedAfterPreview() {
        IdentityProvider scm = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://role-gitlab.example.org"));
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", scm));
        Account administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        long administratorId = Objects.requireNonNull(administrator.getId());
        var preview = personData.preview(
                administratorId, null, List.of(new PersonIdentity(Objects.requireNonNull(scm.getId()), "42", null)));
        administrator.setAppRole(Account.AppRole.USER);
        accounts.saveAndFlush(administrator);
        assertThatThrownBy(() -> personData.requestErasure(preview.request().getId(), administratorId, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThat(personData.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.PREVIEW);
        assertThat(suppression.isUserSuppressed(target.getId())).isFalse();
        assertThat(users.findById(target.getId()).orElseThrow().getLogin()).isEqualTo("target");
    }

    @Test
    void shouldPreserveActualAdministratorWhenAnotherAdministratorUsesThePersonsOwnPreview() {
        Account person = new Account("Person");
        person.setAppRole(Account.AppRole.APP_ADMIN);
        person = accounts.saveAndFlush(person);
        Account administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        long personId = Objects.requireNonNull(person.getId());
        long administratorId = Objects.requireNonNull(administrator.getId());
        var preview = personData.preview(personId, personId, List.of());
        UUID requestId = preview.request().getId();
        assertThatThrownBy(() -> personData.requestErasure(requestId, personId, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Another instance admin");
        personData.requestErasure(requestId, administratorId, true);
        personData.run(requestId);
        var receipt = personData.get(requestId).request();
        assertThat(receipt.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(receipt.getAdministratorAccountId()).isEqualTo(administratorId);
        assertThat(accounts.findById(administratorId).orElseThrow().getStatus()).isEqualTo(Account.Status.ACTIVE);
    }

    @Test
    void shouldEraseCopiedEvidenceAndKeepAnotherDevelopersFeedbackWhenThePersonOnlyAppearsInItsEvidence() {
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://secondary-evidence.example"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        var other = users.saveAndFlush(TestUserFactory.createUser(84L, "OTHER-PROFILE-CANARY", provider));
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        List<DerivedConversation> affected = new ArrayList<>();
        List<UUID> copiedJobs = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("secondary-" + index));
            var derived = seedDerivedConversation(workspace, 1000L + index, other);
            affected.add(derived);
            copiedJobs.add(derived.jobId());
            keepCompositionInputs(derived);
            // Folder removal has already been acknowledged. Exact provenance remains until its counted
            // person step: the absence of the folder must not hide the job that copied the person.
            jdbc.update(
                    """
                    INSERT INTO person_evidence_copy(id,workspace_id,job_id,store_id,state,payload)
                    VALUES (?,?,?,?,'ERASED',CAST(? AS jsonb))
                    """,
                    UUID.randomUUID(),
                    workspace.getId(),
                    derived.jobId(),
                    UUID.randomUUID(),
                    mapper.writeValueAsString(Map.of(
                            "identities",
                            List.of(new PersonCopyIdentity("GITLAB", provider.getServerUrl(), "42", null)),
                            "repositories",
                            List.of())));
        }
        var unrelatedWorkspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("secondary-keep"));
        var unrelated = seedDerivedConversation(unrelatedWorkspace, 2000L, other);
        keepCompositionInputs(unrelated);
        var preview = personData.preview(
                administratorId,
                null,
                List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)));
        UUID requestId = preview.request().getId();
        var scope = mapper.readValue(Objects.requireNonNull(preview.request().getScopeJson()), PersonScope.class);
        assertThat(scope.derivedJobIds()).containsExactlyInAnyOrderElementsOf(copiedJobs);
        var export = personData.export(requestId);
        assertThat(export.path("stores").path("observation")).isEmpty();
        assertThat(export.path("stores").path("feedback")).isEmpty();
        assertThat(export.path("stores").path("feedback_placement")).isEmpty();
        assertThat(export.path("stores").path("mentor_entry_feedback_copy")).isEmpty();
        assertThat(export.path("stores").path("person_evidence_copy")).hasSize(2);
        Map<String, Long> counts = mapper.readValue(preview.request().getCountsJson(), new TypeReference<>() {});
        assertThat(counts.get("agent_job_evidence_copy")).isEqualTo(2L);
        assertThat(counts.get("review_composition_basis")).isEqualTo(2L);
        assertThat(export.path("stores").path("review_composition_basis").toString())
                .doesNotContain("ORIGINAL-BASIS-CANARY");
        assertThat(export.path("stores").path("agent_job")).isEmpty();
        assertThat(export.path("stores").path("agent_job_evidence_copy")).isEmpty();
        assertThat(export.path("stores").path("mentor_entry_tool_result_copy")).isEmpty();
        assertThat(export.toString())
                .doesNotContain(
                        "OTHER-PROFILE-CANARY",
                        "credential-canary",
                        "Practice guidance for developer",
                        "context/conversation.json");
        // The frozen selection names workspaces, so a tool result written after the preview is redacted too.
        Workspace firstAffected =
                workspaces.findById(affected.getFirst().workspaceId()).orElseThrow();
        UUID laterToolResult = seedToolResult(firstAffected, other);
        UUID linkedAnswer = seedLinkedAnswer(
                firstAffected, other, affected.getFirst().observationIds().getFirst());
        personData.requestErasure(requestId, administratorId, true);
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        for (var derived : affected) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM review_composition_basis WHERE job_id=?",
                            Integer.class,
                            derived.jobId()))
                    .isZero();
            assertThat(observationRepository.findAllById(derived.observationIds()))
                    .hasSize(2);
            assertThat(feedbackRepository.findById(derived.preparedId())).isPresent();
            assertThat(feedbackRepository.findById(derived.deliveredId())).isPresent();
            assertThat(mentorEntryRepository
                            .findById(derived.messageId())
                            .orElseThrow()
                            .getText())
                    .isEqualTo("Delivered guidance");
            assertThat(agentJobRepository
                            .findById(derived.jobId())
                            .orElseThrow()
                            .getEvidenceSnapshot())
                    .isNull();
            assertThat(mentorEntryRepository
                            .findById(derived.toolResultId())
                            .orElseThrow()
                            .isRedacted())
                    .isTrue();
        }
        assertThat(mentorEntryRepository.findById(laterToolResult).orElseThrow().isRedacted())
                .isTrue();
        // A tool result that copies no one's data stays, so the other developer still sees the feedback Heph showed.
        assertThat(mentorTimeline
                        .entries(firstAffected.getId(), Set.of(other.getId()), null, MentorTimeline.MAX_PAGE)
                        .entries())
                .filteredOn(entry -> entry.id().equals(linkedAnswer))
                .singleElement()
                .satisfies(entry -> assertThat(entry.observations()).isNotEmpty());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM person_evidence_copy WHERE payload<>'{}'::jsonb", Long.class))
                .isZero();
        assertThat(agentJobRepository.findById(unrelated.jobId()).orElseThrow().getEvidenceSnapshot())
                .isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review_composition_basis WHERE job_id=?",
                        Integer.class,
                        unrelated.jobId()))
                .isEqualTo(1);
        assertThat(mentorEntryRepository
                        .findById(unrelated.toolResultId())
                        .orElseThrow()
                        .isRedacted())
                .isFalse();
        assertThat(users.findById(other.getId()).orElseThrow().getLogin()).isEqualTo("OTHER-PROFILE-CANARY");
        assertThat(suppression.isUserSuppressed(Objects.requireNonNull(target.getId())))
                .isTrue();
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
    }

    private void link(
            Account account, IdentityProvider provider, String subject, @Nullable String team, @Nullable Long actor) {
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(provider.getId()));
        link.setSubject(subject);
        link.setTeamId(team);
        link.setExternalActorId(actor);
        links.saveAndFlush(link);
    }

    private void preference(User user) {
        UserPreferences preference = new UserPreferences();
        preference.setUser(user);
        preferences.saveAndFlush(preference);
    }

    private void membership(Workspace workspace, User user) {
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setWorkspace(workspace);
        membership.setUser(user);
        memberships.saveAndFlush(membership);
    }

    private SlackThread slackThread(Workspace workspace, String root, User... participants) {
        SlackThread thread = new SlackThread();
        thread.setWorkspaceId(workspace.getId());
        thread.setSlackChannelId("C1");
        thread.setSlackThreadTs(root);
        thread.setParticipantMemberIds(
                Arrays.stream(participants).mapToLong(User::getId).toArray());
        thread.setMessageCount(participants.length);
        return slackThreads.saveAndFlush(thread);
    }

    private void message(
            Workspace workspace,
            String team,
            String root,
            String timestamp,
            String nativeUser,
            User member,
            String text) {
        SlackMessage message = new SlackMessage();
        message.setWorkspaceId(workspace.getId());
        message.setSlackTeamId(team);
        message.setSlackChannelId("C1");
        message.setSlackThreadTs(root);
        message.setSlackTs(timestamp);
        message.setAuthorSlackUserId(nativeUser);
        message.setAuthorMemberId(member.getId());
        message.setText(text);
        slackMessages.saveAndFlush(message);
    }

    private record ConversationFeedbackCopy(UUID messageId, UUID replyId) {}

    private List<JsonNode> withoutErasedCopies(
            String store, List<JsonNode> rows, List<ConversationFeedbackCopy> copies) {
        if (!store.equals("mentor_entry")) return rows;
        var copiedIds = copies.stream().map(copy -> copy.messageId().toString()).collect(Collectors.toSet());
        return rows.stream()
                .filter(row -> !copiedIds.contains(row.path("id").asString()))
                .toList();
    }

    private ConversationFeedbackCopy seedFeedbackCopy(DerivedConversation target, DerivedConversation other) {
        MentorEntry otherAnswer =
                mentorEntryRepository.findById(other.messageId()).orElseThrow();
        MentorEntry copied = mentorEntries.append(
                otherAnswer.getWorkspaceId(),
                otherAnswer.getConversationId(),
                entry(
                        MentorEntry.Kind.HEPH,
                        "Target guidance copied into another conversation",
                        piAnswer("Target guidance copied into another conversation")));
        MentorEntry reply = mentorEntries.append(
                otherAnswer.getWorkspaceId(),
                otherAnswer.getConversationId(),
                entry(MentorEntry.Kind.USER, "Other reply to erased feedback", null));
        feedbackPlacementRepository.saveAndFlush(FeedbackPlacement.builder()
                .feedback(feedbackRepository.findById(target.deliveredId()).orElseThrow())
                .placementType(PlacementType.CONVERSATION_TURN)
                .mentorEntryId(copied.getId())
                .createdAt(Instant.now())
                .build());
        return new ConversationFeedbackCopy(copied.getId(), reply.getId());
    }

    private Path captureMountedEvidence(
            EvidenceFolderPersonDataCatalog worker,
            PersonDataCopyRecorder recorder,
            DerivedConversation derived,
            User owner)
            throws Exception {
        var job = agentJobRepository.findById(derived.jobId()).orElseThrow();
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("mounted-privacy-worker");
        agentJobRepository.saveAndFlush(job);
        var files = new JobEvidenceFiles(
                new FabricLayout(evidenceRoot.toString()), agentJobRepository, Clock.systemUTC(), worker);
        files.beginPersonCapture(job);
        recorder.recordUser(owner.getId());
        try (var prepared = files.prepare(
                job,
                new PreparedEvidence(
                        Map.of("context/person.json", "MOUNTED-PROFILE-CANARY".getBytes(StandardCharsets.UTF_8)), null),
                null)) {
            assertThat(prepared.filesOnDisk()).isNotEmpty();
        }
        return evidenceRoot
                .resolve("jobs")
                .resolve(job.getWorkspace().getId().toString())
                .resolve(job.getId().toString());
    }

    private record DerivedConversation(
            List<UUID> observationIds,
            UUID preparedId,
            UUID deliveredId,
            UUID messageId,
            UUID toolResultId,
            UUID jobId,
            long workspaceId) {}

    private static MentorEntryDraft entry(MentorEntry.Kind kind, String text, @Nullable JsonNode payload) {
        return new MentorEntryDraft(kind, MentorEntry.Surface.APP, text, payload, null, null, null);
    }

    /** Heph's answer as the end of its turn stores it, with a tool call whose arguments hold a credential. */
    private JsonNode piAnswer(String text) {
        return MentorLedgerFixtures.toolCall(
                MentorLedgerFixtures.piId(),
                "context-call",
                "fetch_context",
                mapper.createObjectNode().put("authorization", "credential-canary " + text));
    }

    /** A tool result in the owner's conversation that read data without recording whose. */
    private UUID seedToolResult(Workspace workspace, User owner) {
        MentorConversation conversation =
                mentorConversations.findOrCreateMain(workspace.getId(), owner.getId(), Set.of(owner.getId()));
        return mentorEntries
                .append(
                        workspace.getId(),
                        conversation.getId(),
                        entry(
                                MentorEntry.Kind.TOOL_RESULT,
                                "runtime-credential-canary unrelated-profile-canary",
                                MentorLedgerFixtures.toolResult(
                                        MentorLedgerFixtures.piId(),
                                        "context-call",
                                        "fetch_context",
                                        "runtime-credential-canary unrelated-profile-canary",
                                        false)))
                .getId();
    }

    /**
     * A Heph message in the owner's conversation that showed feedback on an observation, with its successful
     * {@code link_observation} result, which copies no one's data.
     */
    private UUID seedLinkedAnswer(Workspace workspace, User owner, UUID observationId) {
        MentorConversation conversation =
                mentorConversations.findOrCreateMain(workspace.getId(), owner.getId(), Set.of(owner.getId()));
        UUID turnId = UUID.randomUUID();
        MentorEntry answer = mentorEntries.append(
                workspace.getId(),
                conversation.getId(),
                new MentorEntryDraft(
                        MentorEntry.Kind.HEPH,
                        MentorEntry.Surface.APP,
                        "",
                        MentorLedgerFixtures.linkObservations(
                                MentorLedgerFixtures.piId(), Map.of("link-call", observationId), "Linked guidance"),
                        turnId,
                        null,
                        null));
        mentorEntries.append(
                workspace.getId(),
                conversation.getId(),
                new MentorEntryDraft(
                        MentorEntry.Kind.TOOL_RESULT,
                        MentorEntry.Surface.APP,
                        "",
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(),
                                "link-call",
                                "link_observation",
                                "Shown to the developer: feedback on observation " + observationId,
                                false),
                        turnId,
                        null,
                        null));
        return answer.getId();
    }

    private DerivedConversation seedDerivedConversation(Workspace workspace, long threadId, User owner) {
        UUID toolResult = seedToolResult(workspace, owner);
        MentorConversation conversation =
                mentorConversations.findOrCreateMain(workspace.getId(), owner.getId(), Set.of(owner.getId()));
        MentorEntry message = mentorEntries.append(
                workspace.getId(),
                conversation.getId(),
                entry(MentorEntry.Kind.HEPH, "Delivered guidance", piAnswer("Delivered guidance")));

        Practice practice = new Practice();
        PracticeTestEvidence.configure(practice, ArtifactKinds.CONVERSATION_THREAD);
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        practice.setWorkspace(workspace);
        practice.setSlug("conv-practice-" + workspace.getId() + "-" + owner.getId());
        practice.setName("Conversation Practice");
        practice.setCriteria("Test description");
        practice = practiceRepository.save(practice);

        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.CONVERSATION_REVIEW);
        job.setArtifactKind(ArtifactKinds.CONVERSATION_THREAD);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setConfigSnapshot(mapper.valueToTree(Map.of("model", "test", "apiKey", "credential-canary")));
        job.setContainerLogs("credential-canary unrelated-profile-canary");
        var snapshot = EvidenceSnapshotFixtures.snapshot(mapper, ArtifactKinds.CONVERSATION_THREAD.value());
        var source = EvidenceSnapshotFixtures.availableSource(snapshot, "slack.conversation.thread", null);
        EvidenceSnapshotFixtures.artifact(snapshot, source, "context/conversation.json", "0".repeat(64));
        job.setEvidenceSnapshot(snapshot);
        job.setMetadata(mapper.valueToTree(Map.of(
                "slack_thread_id",
                threadId,
                "actor_user_id",
                owner.getId(),
                "author_login",
                "unrelated-profile-canary")));
        job = agentJobRepository.save(job);

        List<UUID> observationIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        for (UUID observationId : observationIds) {
            observationRepository.insertIfAbsent(
                    observationId,
                    "occ-" + observationId,
                    job.getId(),
                    job.getWorkspace().getId(),
                    practice.getId(),
                    null,
                    ArtifactKinds.CONVERSATION_THREAD.value(),
                    threadId,
                    owner.getId(),
                    "Observation title",
                    "NOT_MET",
                    "MAJOR",
                    null,
                    null,
                    null,
                    Instant.now(),
                    "LIVE");
        }
        Feedback prepared = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.CONVERSATION_THREAD)
                .artifactId(threadId)
                .recipientUserId(owner.getId())
                .aboutUserId(owner.getId())
                .channel(FeedbackChannel.IN_CHAT)
                .position(0)
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .body("Practice guidance for developer " + owner.getId())
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .build());
        feedbackObservationRepository.insertIfAbsent(
                prepared.getId(), observationIds.get(0), EvidenceRole.PRIMARY.name(), 0);
        Feedback delivered = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.CONVERSATION_THREAD)
                .artifactId(threadId)
                .recipientUserId(owner.getId())
                .aboutUserId(owner.getId())
                .channel(FeedbackChannel.IN_CHAT)
                .position(1)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .body("Practice guidance for developer " + owner.getId())
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .deliveredAt(Instant.now())
                .build());
        feedbackObservationRepository.insertIfAbsent(
                delivered.getId(), observationIds.get(1), EvidenceRole.PRIMARY.name(), 0);
        feedbackPlacementRepository.save(FeedbackPlacement.builder()
                .feedback(delivered)
                .placementType(PlacementType.CONVERSATION_TURN)
                .mentorEntryId(message.getId())
                .createdAt(Instant.now())
                .build());
        Feedback posted = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.CONVERSATION_THREAD)
                .artifactId(threadId)
                .recipientUserId(owner.getId())
                .aboutUserId(owner.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(2)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .body("Provider-posted guidance for developer " + owner.getId())
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .deliveredAt(Instant.now())
                .build());
        feedbackObservationRepository.insertIfAbsent(
                posted.getId(), observationIds.getFirst(), EvidenceRole.PRIMARY.name(), 0);
        feedbackPlacementRepository.save(FeedbackPlacement.builder()
                .feedback(posted)
                .placementType(PlacementType.SUMMARY)
                .postedCommentRef("1700000000." + owner.getId())
                .postedCommentUrl(
                        "https://team-" + workspace.getId() + ".slack.com/archives/C1/p1700000000" + owner.getId())
                .createdAt(Instant.now())
                .build());
        return new DerivedConversation(
                observationIds,
                prepared.getId(),
                delivered.getId(),
                message.getId(),
                toolResult,
                job.getId(),
                workspace.getId());
    }
}
