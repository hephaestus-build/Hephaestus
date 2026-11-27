package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.exception.JournalPendingException;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class PrivacyJournalOutageIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @MockitoBean
    private PrivacyJournalObjectStore bucket;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private PrivacyJournalShipping shipping;

    @Autowired
    private OwnerScope owners;

    @Autowired
    private IdentityRestrictionService restrictions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private PrivacyJournal journal;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebTestClient client;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void shouldWaitForAWorkspaceDeletionFactAndConfirmItOnTheSameRequest() {
        var user = persistUser("journal-requester");
        var workspace =
                createWorkspace("journal-workspace", "Journal workspace", "journal-workspace", AccountType.ORG, user);
        ensureMembership(workspace, user, WorkspaceRole.OWNER);
        var account = accountId(user);
        var thread = conversations.findOrCreateMain(workspace.getId(), account);
        var entry = entries.append(
                workspace.getId(),
                thread.getId(),
                new ConversationEntryDraft(
                        ConversationEntry.Kind.USER,
                        ConversationEntry.Surface.APP,
                        "Delete this question",
                        null,
                        null,
                        null,
                        null));

        when(bucket.store(any(), any())).thenReturn(false);

        expectJournalPending(deleteEntry(workspace.getWorkspaceSlug(), entry.getId(), account));
        assertThat(entries.find(workspace.getId(), entry.getId()).orElseThrow().isRedacted())
                .isTrue();

        when(bucket.store(any(), any())).thenReturn(true);

        deleteEntry(workspace.getWorkspaceSlug(), entry.getId(), account)
                .expectStatus()
                .isNoContent();
        assertThat(jdbc.queryForList("""
                SELECT shipped_at IS NOT NULL FROM privacy_journal
                WHERE kind = 'ENTRY_DELETED' AND refs->>'entryId' = ?
                """, Boolean.class, entry.getId().toString()))
                .containsExactly(true);
    }

    @Test
    void shouldConfirmTheSameGoalDeletionAfterABucketOutage() {
        long account = Objects.requireNonNull(persistAccount("Goal journal requester").getId());
        String token = "mock-jwt-member-" + account;
        String path = "/user/personal-goal";
        client.put().uri(path).headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"revision":0,"words":"Private goal for the journal retry"}
                        """)
                .exchange().expectStatus().isOk();

        when(bucket.store(any(), any())).thenReturn(false);
        expectJournalPending(deleteGoal(path, token));
        client.get().uri(path).headers(headers -> headers.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.words").doesNotExist();

        when(bucket.store(any(), any())).thenReturn(true);
        deleteGoal(path, token).expectStatus().isOk();
        transactions.executeWithoutResult(status -> {
            owners.ofJob(account);
            assertThat(jdbc.queryForList("""
                SELECT j.shipped_at IS NOT NULL AND j.refs = jsonb_build_object('recordId', g.goal_id)
                FROM privacy_journal j
                JOIN personal_goal g ON g.goal_id = j.entry_id
                WHERE j.kind = 'RECORD_DELETED' AND g.account_id = ?
                """, Boolean.class, account)).containsExactly(true);
        });
    }

    private WebTestClient.ResponseSpec deleteGoal(String path, String token) {
        return client.delete().uri(path + "?revision=1").headers(headers -> headers.setBearerAuth(token)).exchange();
    }

    private WebTestClient.ResponseSpec deleteEntry(String slug, UUID entryId, long account) {
        return client.delete()
                .uri("/workspaces/{slug}/conversation/entries/{entryId}", slug, entryId)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .exchange();
    }

    @Test
    void shouldFinishBackgroundCommitCallbacksDuringABucketOutage() {
        UUID id = UUID.randomUUID();
        var callbackRan = new AtomicBoolean();
        when(bucket.store(any(), any())).thenReturn(false);

        transactions.executeWithoutResult(status -> {
            journal.record(id, PrivacyJournalKind.MEMBERSHIP_ENDED, Map.of("workspaceId", 7L, "accountId", 8L));
            TransactionCallbacks.afterCommit(() -> callbackRan.set(true));
        });

        assertThat(callbackRan).isTrue();
        assertThat(jdbc.queryForList(
                        "SELECT shipped_at IS NULL FROM privacy_journal WHERE entry_id = ?", Boolean.class, id))
                .containsExactly(true);
    }

    @Test
    void keepsErasureOnHoldWhileAFactFromAnErasureStepWaitsForTheBucket() {
        var administrator = new Account("Recovery operator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        var administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Person on hold")).getId());
        var requestId = personData
                .preview(administratorId, accountId, List.of())
                .request()
                .getId();

        when(bucket.store(any(), any())).thenReturn(true);
        erasureReply(requestId, administratorId, false).expectStatus().isOk();

        var entryId = UUID.randomUUID();
        var refs = Map.<String, Object>of("workspaceId", 7L, "entryId", entryId);
        when(bucket.store(any(), any())).thenReturn(false);
        ScopedValue.where(PrivacyJournalShipping.ERASURE_REQUEST, requestId)
                .run(() -> transactions.executeWithoutResult(
                        status -> journal.record(PrivacyJournalKind.ENTRY_DELETED, refs)));
        personData.run(requestId);
        expectJournalPending(erasureReply(requestId, administratorId, false));
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.ERASING);

        when(bucket.store(any(), any())).thenReturn(true);
        erasureReply(requestId, administratorId, false).expectStatus().isOk();
        completeAfterShipping(requestId);
    }

    @Test
    void keepsNativeErasureOnHoldWhileExternalCopyConfirmationWaitsForTheBucket() {
        var administrator = new Account("Recovery operator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        var administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var providerId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() ->
                        providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")))
                .getId());
        var subject = new PersonIdentity(providerId, "983157561", null);
        var requestId = personData
                .preview(administratorId, null, List.of(subject))
                .request()
                .getId();

        when(bucket.store(any(), any())).thenReturn(true);
        erasureReply(requestId, administratorId, false).expectStatus().isOk();
        // Model an interrupted request that still needs confirmation of external copies.
        jdbc.update("""
                UPDATE person_data_request SET state = 'FAILED', failure_code = 'EXTERNAL_COPIES_REQUIRE_REMOVAL'
                WHERE id = ?
                """, requestId);

        when(bucket.store(any(), any())).thenReturn(false);
        expectJournalPending(erasureReply(requestId, administratorId, true));
        assertThat(restrictions.isSuppressed(providerId, subject.subject(), null))
                .isTrue();
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.ERASING);
        assertThat(personData.get(requestId).request().getCompletedJson()).isEqualTo("{}");

        when(bucket.store(any(), any())).thenReturn(true);
        erasureReply(requestId, administratorId, true).expectStatus().isOk();
        completeAfterShipping(requestId);
    }

    @Test
    void shouldSuppressProviderIdentityWhenSelfServiceErasureWaitsForTheBucket() {
        var account = accounts.saveAndFlush(new Account("Account pending erasure"));
        long accountId = Objects.requireNonNull(account.getId());
        long providerId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() ->
                        providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")))
                .getId());
        String subject = Long.toString(accountId + 1_000_000_000);
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(providerId);
        identity.setProviderUserKey(subject);
        identity.setTeamKey("");
        identities.saveAndFlush(identity);
        String token = "mock-jwt-member-" + accountId;
        when(bucket.store(any(), any())).thenReturn(false);

        client.delete()
                .uri("/user")
                .header("X-Confirm-Delete", Long.toString(accountId))
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.JOURNAL_PENDING.uri().toString());

        assertThat(restrictions.isSuppressed(providerId, subject, null)).isTrue();
        assertThat(jdbc.queryForList(
                        "SELECT state FROM person_data_request WHERE scope_json::jsonb->>'accountId' = ?",
                        String.class,
                        Long.toString(accountId)))
                .containsExactly(PersonDataRequest.State.ERASING.name());
        assertThat(accounts.findById(accountId).orElseThrow().getDisplayName()).isEqualTo("Account pending erasure");

        var id = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT id FROM person_data_request WHERE scope_json::jsonb->>'accountId' = ?
                """, UUID.class, Long.toString(accountId)));
        personData.run(id);
        assertThat(personData.get(id).request().getState()).isEqualTo(PersonDataRequest.State.ERASING);

        when(bucket.store(any(), any())).thenReturn(true);
        var pending = jdbc.queryForList("""
                SELECT entry_id FROM privacy_journal
                WHERE entry_id = ? OR refs->>'accountId' = ?
                """, UUID.class, id, Long.toString(accountId));
        pending.forEach(entry -> shipping.confirm(Objects.requireNonNull(entry)));
        completeAfterShipping(id);
    }

    @Test
    void shouldReturnPromptlyWhenACaptureHoldsTheCopyFence() {
        long accountId = Objects.requireNonNull(accounts.saveAndFlush(new Account("Account with a capture in progress"))
                .getId());
        String token = "mock-jwt-member-" + accountId;

        transactions.executeWithoutResult(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock_shared(2165, 1)");
            client.mutate()
                    .responseTimeout(Duration.ofSeconds(4))
                    .build()
                    .delete()
                    .uri("/user")
                    .header("X-Confirm-Delete", Long.toString(accountId))
                    .headers(headers -> headers.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409);
        });

        assertThat(accounts.findById(accountId).orElseThrow().getStatus()).isEqualTo(Account.Status.ACTIVE);
        assertThat(restrictions.isAccountSuppressed(accountId)).isFalse();
        verifyNoInteractions(bucket);
    }

    @Test
    void shouldQueueTheErasureAheadOfNewCaptures() throws Exception {
        long accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Account awaiting erasure")).getId());
        when(bucket.store(any(), any())).thenReturn(true);
        var reply = transactions.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock_shared(2165, 1)");
            var request = CompletableFuture.supplyAsync(() -> client.delete()
                    .uri("/user")
                    .header("X-Confirm-Delete", Long.toString(accountId))
                    .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + accountId))
                    .exchange());
            await().atMost(Duration.ofSeconds(2))
                    .until(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_locks WHERE locktype = 'advisory'
                            AND classid = 2165 AND objid = 1 AND mode = 'ExclusiveLock' AND NOT granted)
                    """, Boolean.class)));
            var lateCapture = CompletableFuture.supplyAsync(
                    () -> jdbc.queryForObject("SELECT pg_try_advisory_xact_lock_shared(2165, 1)", Boolean.class));
            assertThat(lateCapture.join()).isFalse();
            return request;
        });
        Objects.requireNonNull(reply).get(10, TimeUnit.SECONDS).expectStatus().isNoContent();
        assertThat(restrictions.isAccountSuppressed(accountId)).isTrue();
    }

    @Test
    void shouldShipLaterFactsEvenWhenTheFirstBatchKeepsFailing() {
        var ready = UUID.randomUUID();
        var rejected = UUID.randomUUID();
        var refs = Map.<String, Object>of("workspaceId", 7L, "accountId", 8L);
        transactions.executeWithoutResult(status -> {
            journal.record(rejected, PrivacyJournalKind.MEMBERSHIP_ENDED, refs);
            for (int i = 0; i < 24; i++) journal.record(PrivacyJournalKind.MEMBERSHIP_ENDED, refs);
            journal.record(ready, PrivacyJournalKind.MEMBERSHIP_ENDED, refs);
        });
        when(bucket.store(any(), any()))
                .thenAnswer(call -> call.<String>getArgument(0).endsWith(ready + ".json"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            shipping.retry();
            assertThat(jdbc.queryForList(
                            "SELECT shipped_at IS NOT NULL FROM privacy_journal WHERE entry_id = ?",
                            Boolean.class,
                            ready))
                    .containsExactly(true);
        });
        assertThat(jdbc.queryForList(
                        "SELECT shipped_at IS NULL FROM privacy_journal WHERE entry_id = ?", Boolean.class, rejected))
                .containsExactly(true);
    }

    @Test
    void shouldHoldThePersonAndWaitForTheJournalBeforeErasingTheirData() {
        var administrator = new Account("Recovery operator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        var administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Person on hold")).getId());

        var id = personData
                .preview(administratorId, accountId, List.of())
                .request()
                .getId();
        when(bucket.store(any(), any())).thenReturn(false);
        expectJournalPending(erasureReply(id, administratorId, false));

        var meters = new SimpleMeterRegistry();
        try {
            var replicaWithoutBucket = new PrivacyJournalShipping(jdbc, mapper, Optional.empty(), meters);
            assertThat(replicaWithoutBucket.erasurePending(id)).isTrue();
            assertThat(replicaWithoutBucket.accountErasurePending(accountId)).isTrue();
            assertThatThrownBy(() -> new PrivacyJournalReply(replicaWithoutBucket).invoke(() -> {
                        replicaWithoutBucket.pendingErasureReply(id);
                        return null;
                    }))
                    .isInstanceOf(JournalPendingException.class);
        } finally {
            meters.close();
        }
        assertThat(restrictions.isAccountSuppressed(accountId)).isTrue();
        Boolean canIssue = transactions.execute(status -> accounts.lockForIssuance(accountId));
        assertThat(canIssue).isFalse();

        personData.run(id);
        assertThat(personData.get(id).request().getState()).isEqualTo(PersonDataRequest.State.ERASING);
        assertThat(accounts.findById(accountId).orElseThrow().getDisplayName()).isEqualTo("Person on hold");

        when(bucket.store(any(), any())).thenReturn(true);
        erasureReply(id, administratorId, false).expectStatus().isOk();
        completeAfterShipping(id);
        assertThat(accounts.findById(accountId).orElseThrow().getStatus()).isEqualTo(Account.Status.DELETED);
    }

    private void completeAfterShipping(UUID id) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            shipping.retry();
            personData.run(id);
            assertThat(personData.get(id).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        });
    }

    private WebTestClient.ResponseSpec erasureReply(UUID id, long administratorId, boolean externalCopiesRemoved) {
        return client.post()
                .uri("/admin/person-data/{id}/erase", id)
                .headers(headers -> headers.setBearerAuth("mock-jwt-admin-" + administratorId))
                .bodyValue(Map.of("externalCopiesRemoved", externalCopiesRemoved))
                .exchange();
    }

    private static void expectJournalPending(WebTestClient.ResponseSpec reply) {
        reply.expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.JOURNAL_PENDING.uri().toString());
    }
}
