package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class NativeActionRecipientIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private AgentActions actions;

    @Autowired
    private ActionPersonDataCatalog catalog;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ApplicationContext context;

    private PersonIdentity recipient;
    private Workspace workspace;
    private long ownerAccountId;

    @BeforeEach
    void setUp() {
        var owner = persistUser("native-action-owner");
        ownerAccountId = accountId(owner);
        workspace = createWorkspace("native-action", "Native actions", "native-action", AccountType.ORG, owner);
        transactions.executeWithoutResult(status -> providers.registerSourceInstance("SLACK", "https://slack.com"));
        long providerId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseThrow()
                .getId());
        recipient = new PersonIdentity(providerId, "U" + UUID.randomUUID(), "T_NATIVE");
    }

    private UUID enqueue(long workspaceId, PersonIdentity target) {
        String key = "native-test:" + UUID.randomUUID();
        return actions.enqueue(new ActionRequest(
                ActionKind.SLACK_NOTICE,
                workspaceId,
                key,
                key,
                mapper.createObjectNode().put("channelId", "D_TEST"),
                null,
                1,
                null,
                target));
    }

    private ActionExecutor executor(AtomicInteger writes) {
        return ActionTestSupport.withAdapter(context, new ActionAdapter() {
            @Override
            public ActionKind kind() {
                return ActionKind.SLACK_NOTICE;
            }

            @Override
            public ActionVariants variants() {
                return ActionVariants.one(ActionTestSupport.Untyped.class);
            }

            @Override
            public ActionResult execute(ActionAttempt attempt) {
                assertThat(attempt.nativeRecipient()).isEqualTo(recipient);
                if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
                writes.incrementAndGet();
                return new ActionResult.Done("external-receipt");
            }
        });
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM agent_action WHERE workspace_id=? AND id=?)",
                Boolean.class,
                workspace.getId(),
                id));
    }

    @Test
    void shouldEraseAnUnlinkedRecipientsPendingActionBeforeItCanWrite() {
        UUID id = enqueue(workspace.getId(), recipient);
        var otherOwner = persistUser("native-action-other-owner");
        var otherWorkspace =
                createWorkspace("native-other", "Other native actions", "native-other", AccountType.ORG, otherOwner);
        UUID other = enqueue(otherWorkspace.getId(), recipient);
        var writes = new AtomicInteger();

        transactions.executeWithoutResult(status ->
                assertThat(actions.eraseRecipient(workspace.getId(), recipient)).isEqualTo(1));

        assertThat(executor(writes).runNow(id)).isEmpty();
        assertThat(writes).hasValue(0);
        assertThat(exists(id)).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM agent_action WHERE workspace_id=? AND id=?)",
                        Boolean.class,
                        otherWorkspace.getId(),
                        other))
                .isTrue();
    }

    @Test
    void shouldExportAndEraseTheExactNativeIdentityAfterItsPayloadWasCleared() {
        UUID id = enqueue(workspace.getId(), recipient);
        var otherTeam = new PersonIdentity(recipient.providerId(), recipient.subject(), "T_OTHER");
        UUID other = enqueue(workspace.getId(), otherTeam);
        assertThat(executor(new AtomicInteger()).runNow(id)).containsInstanceOf(ActionResult.Done.class);
        assertThat(jdbc.queryForObject(
                        "SELECT payload IS NULL FROM agent_action WHERE workspace_id=? AND id=?",
                        Boolean.class,
                        workspace.getId(),
                        id))
                .isTrue();
        long providerId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseThrow()
                .getId());
        var scope = new PersonScope(
                null, List.of(new PersonIdentity(providerId, recipient.subject(), recipient.teamId())), List.of());
        var contributor = catalog.contributors().getFirst();
        var selected = contributor.select(scope);

        var exported = contributor.export(selected, scope);

        assertThat(exported).hasSize(1);
        assertThat(exported.getFirst().toString()).contains(id.toString(), recipient.subject());
        transactions.executeWithoutResult(
                status -> assertThat(contributor.erase(selected, scope)).isEqualTo(1));
        assertThat(exists(id)).isFalse();
        assertThat(exists(other)).isTrue();
    }

    @Test
    void shouldEraseNativeOnlyActionsWhenTheirRecipientLaterLinksAnAccount() {
        UUID id = enqueue(workspace.getId(), recipient);
        long providerId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseThrow()
                .getId());
        jdbc.update("""
                INSERT INTO identity(account_id,provider_id,provider_user_key,team_key,linked_at,linked_via)
                VALUES (?,?,?,?,now(),'MANUAL')
                """, ownerAccountId, providerId, recipient.subject(), recipient.teamId());

        transactions.executeWithoutResult(status -> catalog.eraseAccount(ownerAccountId));

        assertThat(exists(id)).isFalse();
    }

    @Test
    void shouldKeepNativeRecipientsImmutableWhileAnActionIsLive() {
        UUID id = enqueue(workspace.getId(), recipient);

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE agent_action SET native_recipient=NULL WHERE workspace_id=? AND id=?",
                        workspace.getId(),
                        id))
                .hasStackTraceContaining("an action identity is immutable");
        assertThat(exists(id)).isTrue();
    }

    private long newAccount(String name) {
        return Objects.requireNonNull(accounts.save(new Account(name)).getId());
    }

    private long slackProviderId() {
        return Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseThrow()
                .getId());
    }

    private void link(long accountId, PersonIdentity identity) {
        jdbc.update("""
                INSERT INTO identity(account_id,provider_id,provider_user_key,team_key,linked_at,linked_via)
                VALUES (?,?,?,?,now(),'MANUAL')
                """, accountId, identity.providerId(), identity.subject(), identity.teamId());
    }

    /** An email to the account, as an instance-level notice. */
    private UUID emailTo(long accountId, JsonNode payload) {
        String key = "email-to:" + UUID.randomUUID();
        return actions.enqueue(new ActionRequest(ActionKind.EMAIL, null, key, key, payload, accountId, 3, null, null));
    }

    /** A role-change notice to {@code recipient} that names {@code named} as the subject or the actor. */
    private UUID roleNotice(long recipient, String field, long named) {
        var payload = JsonNodeFactory.instance.objectNode().put("kind", "ROLE_CHANGED");
        payload.putObject("event").putObject("change").put(field, named);
        return emailTo(recipient, payload);
    }

    private JsonNode exportedRow(List<JsonNode> exported, UUID id) {
        return exported.stream()
                .filter(row -> row.path("id").asString().equals(id.toString()))
                .findFirst()
                .orElseThrow();
    }

    private boolean present(UUID id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_action WHERE id=?)", Boolean.class, id));
    }

    private String payloadText(UUID id) {
        return jdbc.queryForObject("SELECT payload::text FROM agent_action WHERE id=?", String.class, id);
    }

    @Test
    void shouldExportAndEraseOnlyThePersonsOwnRowsAndAnonymizeTheirRoleReferences() {
        long accountA = ownerAccountId;
        long accountB = newAccount("Role recipient B");
        long providerId = slackProviderId();
        var identity = new PersonIdentity(providerId, recipient.subject(), recipient.teamId());
        link(accountA, identity);
        String otherUrl = "https://enterprise-" + UUID.randomUUID() + ".slack.test";
        transactions.executeWithoutResult(status -> providers.registerSourceInstance("SLACK", otherUrl));
        long otherProvider = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, otherUrl)
                .orElseThrow()
                .getId());
        UUID own = emailTo(accountA, JsonNodeFactory.instance.objectNode().put("kind", "TEST_MESSAGE"));
        UUID natives = enqueue(workspace.getId(), identity);
        UUID reference = roleNotice(accountB, "actorAccountId", accountA);
        UUID otherTeam = enqueue(workspace.getId(), new PersonIdentity(providerId, recipient.subject(), "T_OTHER"));
        UUID otherInstance =
                enqueue(workspace.getId(), new PersonIdentity(otherProvider, recipient.subject(), recipient.teamId()));
        var scope = new PersonScope(accountA, List.of(identity), List.of());
        var contributor = catalog.contributors().getFirst();
        var selected = contributor.select(scope);

        var exported = contributor.export(selected, scope);

        assertThat(exportedRow(exported, own).has("payload")).isTrue();
        var nativeRow = exportedRow(exported, natives);
        assertThat(nativeRow.has("payload")).isTrue();
        assertThat(nativeRow.path("native_recipient").path("subject").asString())
                .isEqualTo(recipient.subject());
        var proof = exportedRow(exported, reference);
        assertThat(proof.propertyNames()).containsExactlyInAnyOrder("id", "kind", "state");
        assertThat(exported.stream().map(row -> row.path("id").asString()).toList())
                .doesNotContain(otherTeam.toString(), otherInstance.toString());

        transactions.executeWithoutResult(
                status -> assertThat(contributor.erase(selected, scope)).isEqualTo(3));

        assertThat(present(own)).isFalse();
        assertThat(present(natives)).isFalse();
        assertThat(present(otherTeam)).isTrue();
        assertThat(present(otherInstance)).isTrue();
        var anonymized = mapper.readTree(payloadText(reference)).path("event").path("change");
        assertThat(anonymized.has("actorAccountId")).isFalse();
        assertThat(anonymized.path("actorAnonymized").asBoolean()).isTrue();
    }

    @Test
    void shouldCountARowThatIsBothOwnedAndAReferenceOnce() {
        long accountA = ownerAccountId;
        long accountB = newAccount("Role recipient B");
        UUID overlap = roleNotice(accountA, "accountId", accountA);
        UUID other = roleNotice(accountB, "accountId", accountA);
        var scope = new PersonScope(accountA, List.of(), List.of());
        var contributor = catalog.contributors().getFirst();
        var selected = contributor.select(scope);

        transactions.executeWithoutResult(
                status -> assertThat(contributor.erase(selected, scope)).isEqualTo(2));

        assertThat(present(overlap)).isFalse();
        assertThat(mapper.readTree(payloadText(other))
                        .path("event")
                        .path("change")
                        .has("accountId"))
                .isFalse();
    }

    @Test
    void shouldNotAnonymizeRoleNoticesWhenAnUnlinkedNativePersonIsErased() {
        long accountB = newAccount("Role recipient B");
        long accountC = newAccount("Role subject C");
        UUID natives = enqueue(workspace.getId(), recipient);
        UUID reference = roleNotice(accountB, "accountId", accountC);
        String before = payloadText(reference);
        var scope = new PersonScope(null, List.of(recipient), List.of());
        var contributor = catalog.contributors().getFirst();
        var selected = contributor.select(scope);

        transactions.executeWithoutResult(
                status -> assertThat(contributor.erase(selected, scope)).isEqualTo(1));

        assertThat(present(natives)).isFalse();
        assertThat(payloadText(reference)).isEqualTo(before);
    }

    @Test
    void shouldKeepAFrozenScopeToItsIdentitiesWhileAccountPurgeReachesTheLaterLink() {
        long accountA = ownerAccountId;
        long accountB = newAccount("Role recipient B");
        UUID natives = enqueue(workspace.getId(), recipient);
        UUID reference = roleNotice(accountB, "actorAccountId", accountA);
        var scope = new PersonScope(accountA, List.of(), List.of());
        var contributor = catalog.contributors().getFirst();
        var selected = contributor.select(scope);
        link(accountA, recipient);

        transactions.executeWithoutResult(status -> contributor.erase(selected, scope));

        assertThat(present(natives))
                .as("the frozen scope did not name this identity")
                .isTrue();
        assertThat(mapper.readTree(payloadText(reference))
                        .path("event")
                        .path("change")
                        .has("actorAccountId"))
                .isFalse();

        transactions.executeWithoutResult(status -> catalog.eraseAccount(accountA));

        assertThat(present(natives)).as("purge reads the current links").isFalse();
    }

    @Test
    void shouldAllowOnlyTheRoleRedactionOfALivePayloadAndKeepTheNativeRecipientImmutable() {
        long accountA = ownerAccountId;
        long accountB = newAccount("Role recipient B");
        UUID reference = roleNotice(accountB, "actorAccountId", accountA);
        UUID natives = enqueue(workspace.getId(), recipient);

        int redacted = jdbc.update("""
                UPDATE agent_action SET payload = jsonb_set(payload, '{event,change}',
                    redact_role_change(payload #> '{event,change}', ?)) WHERE id = ?
                """, accountA, reference);

        assertThat(redacted).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_action SET native_recipient = NULL WHERE id = ?", natives))
                .hasStackTraceContaining("an action identity is immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_action SET payload = '{\"n\":2}' WHERE id = ?", natives))
                .hasStackTraceContaining("an action payload changes only to erase role-notice identities");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE agent_action SET payload = jsonb_set(payload, '{event,change,role}', '\"OWNER\"'::jsonb) WHERE id = ?",
                        reference))
                .hasStackTraceContaining("an action payload changes only to erase role-notice identities");
    }
}
