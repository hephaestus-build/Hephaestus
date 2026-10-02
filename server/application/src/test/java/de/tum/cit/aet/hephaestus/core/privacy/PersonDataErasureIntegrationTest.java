package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.account.*;
import de.tum.cit.aet.hephaestus.agent.*;
import de.tum.cit.aet.hephaestus.agent.job.*;
import de.tum.cit.aet.hephaestus.core.auth.domain.*;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.integration.core.connection.*;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.*;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.*;
import de.tum.cit.aet.hephaestus.integration.slack.domain.*;
import de.tum.cit.aet.hephaestus.mentor.*;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.*;
import de.tum.cit.aet.hephaestus.practices.model.*;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.*;
import de.tum.cit.aet.hephaestus.workspace.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class PersonDataErasureIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PersonDataService personData;

    @Autowired
    private PersonProcessingSuppression suppression;

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
    private ChatThreadRepository chatThreadRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

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

    @BeforeEach
    void clearRows() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void erasesTargetAcrossTwoWorkspacesAndEveryRegisteredStoreWithoutTouchingAnotherPerson() {
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
                1L,
                IntegrationKind.GITHUB,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                targetAccount.getId());
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
        List<DerivedConversation> targetDerived = new ArrayList<>();
        List<DerivedConversation> otherDerived = new ArrayList<>();
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
        // Source-derived copies can belong to multiple participants. Compare the other person's
        // primary rows, not the target's guidance from their shared source conversation.
        PersonScope otherPrimaryScope =
                new PersonScope(otherScope.accountId(), otherScope.identities(), otherScope.userIds());
        var otherSelection = registry.select(otherPrimaryScope);
        Map<String, Object> otherRows = new TreeMap<>();
        for (var contributor : registry.stores())
            otherRows.put(
                    contributor.store(),
                    contributor.export(Objects.requireNonNull(otherSelection.get(contributor.store()))));
        long administratorId = Objects.requireNonNull(administrator.getId());
        var preview = personData.preview(administratorId, targetAccount.getId(), List.of());
        UUID requestId = preview.request().getId();
        var export = personData.export(requestId);
        Map<String, Long> counts = mapper.readValue(preview.request().getCountsJson(), new TypeReference<>() {});
        assertThat(counts.get("oauth_state_nonce")).isEqualTo(1L);
        assertThat(counts.get("client_session")).isEqualTo(1L);
        assertThat(counts.get("client_sign_in_handoff")).isEqualTo(1L);
        assertThat(export.path("stores").path("client_sign_in_handoff").toString())
                .doesNotContain(targetSession.toString(), "code_hash", "code_challenge");

        assertThat(counts.get("feedback")).isEqualTo(4L);
        assertThat(counts.get("observation")).isEqualTo(4L);
        assertThat(counts.get("observation_invalidation")).isEqualTo(2L);
        assertThat(counts.get("config_audit_event_membership_subject")).isEqualTo(2L);
        assertThat(counts.get("chat_thread")).isEqualTo(2L);
        assertThat(counts.get("slack_message")).isEqualTo(2L);
        counts.forEach((store, count) -> assertThat(
                        (long) export.path("stores").path(store).size())
                .as("Frozen preview/export parity for %s", store)
                .isEqualTo(count));
        assertThat(export.path("stores").path("chat_thread").toString()).doesNotContain("session_jsonl");
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
        for (long threadId : sharedThreads) {
            long workspaceId = Objects.requireNonNull(
                    jdbc.queryForObject("SELECT workspace_id FROM slack_thread WHERE id=?", Long.class, threadId));
            assertThat(suppression.isArtifactSuppressed(workspaceId, "chat.conversation_thread", threadId))
                    .isTrue();
            assertThat(suppression.isArtifactSuppressed(-1L, "chat.conversation_thread", threadId))
                    .isFalse();
        }
        personData.run(requestId);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM oauth_state_nonce WHERE nonce='nonce-credential-canary'", Long.class))
                .isZero();
        var receipt = personData.get(requestId).request();
        assertThat(receipt.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(receipt.getScopeJson()).isNull();
        assertThat(receipt.getSelectionsJson()).isNull();
        Map<String, Long> completed = mapper.readValue(receipt.getCompletedJson(), new TypeReference<>() {});
        assertThat(completed.keySet()).containsExactlyInAnyOrderElementsOf(counts.keySet());
        assertThat(accounts.findById(Objects.requireNonNull(targetAccount.getId()))
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(Account.Status.DELETED);
        assertThat(users.findById(target.getId()).orElseThrow().getLogin()).startsWith("erased-");
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
        for (var derived : targetDerived) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM observation WHERE id IN (?,?)",
                            Long.class,
                            derived.observationIds().get(0),
                            derived.observationIds().get(1)))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM feedback WHERE id IN (?,?)",
                            Long.class,
                            derived.preparedId(),
                            derived.deliveredId()))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM chat_message WHERE id=?", Long.class, derived.messageId()))
                    .isZero();
        }
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
        for (var contributor : registry.stores()) {
            // A shared thread's aggregate changes; the other person's participant projection does not.
            assertThat(contributor.export(Objects.requireNonNull(otherSelection.get(contributor.store()))))
                    .as("Another person's %s rows", contributor.store())
                    .isEqualTo(otherRows.get(contributor.store()));
        }
        personData.requestErasure(requestId, administratorId, true);
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getCompletedJson()).isEqualTo(receipt.getCompletedJson());
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
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("exact Slack identity");
        assertThatThrownBy(() -> resolver.resolve(null, List.of(slackKey)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
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
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
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
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Another administrator");
        personData.requestErasure(requestId, administratorId, true);
        personData.run(requestId);
        var receipt = personData.get(requestId).request();
        assertThat(receipt.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(receipt.getAdministratorAccountId()).isEqualTo(administratorId);
        assertThat(accounts.findById(administratorId).orElseThrow().getStatus()).isEqualTo(Account.Status.ACTIVE);
    }

    private void link(
            Account account,
            IdentityProvider provider,
            String subject,
            @org.jspecify.annotations.Nullable String team,
            @org.jspecify.annotations.Nullable Long actor) {
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

    private record DerivedConversation(List<UUID> observationIds, UUID preparedId, UUID deliveredId, UUID messageId) {}

    private DerivedConversation seedDerivedConversation(Workspace workspace, long threadId, User owner) {
        ChatThread chatThread = new ChatThread();
        chatThread.setSessionJsonl(
                "{\"type\":\"toolResult\",\"body\":\"runtime-credential-canary unrelated-profile-canary\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        chatThread.setId(UUID.randomUUID());
        chatThread.setWorkspace(workspace);
        chatThread.setUser(owner);
        chatThreadRepository.save(chatThread);
        ChatMessage message = new ChatMessage();
        message.setId(UUID.randomUUID());
        message.setThread(chatThread);
        message.setRole(ChatMessage.Role.ASSISTANT);
        message.setStatus(ChatMessage.Status.completed);
        message.setParts(mapper.valueToTree(List.of(Map.of("type", "text", "text", "Delivered guidance"))));
        message.setMetadata(mapper.createObjectNode());
        chatMessageRepository.save(message);

        Practice practice = new Practice();
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.CONVERSATION_THREAD));
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
                    "ASSESSED",
                    "ABSENT",
                    "GOOD",
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
                .chatMessageId(message.getId())
                .createdAt(Instant.now())
                .build());
        return new DerivedConversation(observationIds, prepared.getId(), delivered.getId(), message.getId());
    }
}
