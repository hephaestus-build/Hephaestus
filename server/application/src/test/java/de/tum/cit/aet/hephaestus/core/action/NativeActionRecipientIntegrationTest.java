package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import tools.jackson.databind.ObjectMapper;

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
}
