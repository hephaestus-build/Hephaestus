package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxClaims;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonConversationCopySource;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.mentor.MentorContextChanges;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.adapter.MentorPersonDataCatalog;
import de.tum.cit.aet.hephaestus.mentor.spi.ConversationSandboxCopies;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** A conversation-copy receipt cannot commit while its physical sandbox still has the selected person's data. */
class ConversationSandboxErasureIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ApplicationContext beans;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TransactionTemplate transactions;

    @TempDir
    Path root;

    @Test
    void shouldAcknowledgeOnlyAfterPhysicalCopiesAreGoneAndKeepOtherWorkspacesAndReviews() throws Exception {
        var person = persistUser("conversation-copy-person");
        var workspace = createWorkspace("conversation-copy-person", "Copy", "copy-org", AccountType.ORG, person);
        var otherPerson = persistUser("conversation-copy-other");
        var otherWorkspace =
                createWorkspace("conversation-copy-other", "Other", "copy-other", AccountType.ORG, otherPerson);
        long personId = accountId(person);
        var administrator = new Account("Erasure administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId = Objects.requireNonNull(
                accountRepository.saveAndFlush(administrator).getId());
        var claims = new PhysicalClaims(root);
        var owner = SandboxOwnerFixtures.owner(
                beans,
                new AttemptTokenIssuer(AttemptKeyFixtures.keyring(AttemptSigningKey.generate())),
                beans.getBean(AgentRuns.class),
                claims);
        var copy = owner.ensure(workspace.getId(), "conversation:" + UUID.randomUUID(), AgentSandboxKind.CONVERSATION);
        var other = owner.ensure(
                otherWorkspace.getId(), "conversation:" + UUID.randomUUID(), AgentSandboxKind.CONVERSATION);
        var review = owner.ensure(workspace.getId(), "review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        Path copiedPerson = claims.copy(copy.claimName());
        Path otherCopy = claims.copy(other.claimName());
        Path reviewCopy = claims.copy(review.claimName());
        var offline = service(SandboxOwnerFixtures.revocations(beans, null));
        var request = Objects.requireNonNull(
                        transactions.execute(status -> offline.preview(administratorId, personId, List.of())))
                .request();
        transactions.executeWithoutResult(status -> offline.requestErasure(request.getId(), administratorId, true));

        offline.run(request.getId());

        assertThat(offline.get(request.getId()).request().getState()).isEqualTo(PersonDataRequest.State.FAILED);
        assertNoAcknowledgement(request.getId());
        assertSandbox(copy, "REQUESTED");
        assertThat(copiedPerson).exists();
        assertThat(otherCopy).exists();
        assertThat(reviewCopy).exists();
        var online = service(SandboxOwnerFixtures.revocations(beans, owner));
        claims.failDelete = true;
        transactions.executeWithoutResult(status -> online.requestErasure(request.getId(), administratorId, true));

        online.run(request.getId());

        assertThat(online.get(request.getId()).request().getState()).isEqualTo(PersonDataRequest.State.FAILED);
        assertNoAcknowledgement(request.getId());
        assertSandbox(copy, "DELETING");
        assertThat(copiedPerson).exists();
        assertThat(otherCopy).exists();
        assertThat(reviewCopy).exists();
        claims.failDelete = false;
        transactions.executeWithoutResult(status -> online.requestErasure(request.getId(), administratorId, true));

        online.run(request.getId());

        var completed = online.get(request.getId()).request();
        assertThat(completed.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(mapper.readTree(Objects.requireNonNull(completed.getCompletedJson()))
                        .has(MentorEntryRepository.COPIES_PERSON_DATA_STORE))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_sandbox WHERE id=? AND workspace_id=?",
                        Integer.class,
                        copy.id(),
                        copy.workspaceId()))
                .isZero();
        assertThat(copiedPerson).doesNotExist();
        assertThat(claims.names()).doesNotContain(copy.claimName());
        assertSandbox(other, "REQUESTED");
        assertSandbox(review, "REQUESTED");
        assertThat(otherCopy).exists();
        assertThat(reviewCopy).exists();
    }

    @Test
    void shouldKeepFrozenPhysicalCustodyAfterItsSandboxRowIsPurgedAndRemoveNewerCopiesToo() throws Exception {
        var person = persistUser("purged-conversation-copy-person");
        var workspace =
                createWorkspace("purged-conversation-copy-person", "Copy", "purged-copy-org", AccountType.ORG, person);
        long personId = accountId(person);
        var administrator = new Account("Purged-copy erasure administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId = Objects.requireNonNull(
                accountRepository.saveAndFlush(administrator).getId());
        var claims = new PhysicalClaims(root);
        var owner = SandboxOwnerFixtures.owner(
                beans,
                new AttemptTokenIssuer(AttemptKeyFixtures.keyring(AttemptSigningKey.generate())),
                beans.getBean(AgentRuns.class),
                claims);
        String subject = "conversation:" + UUID.randomUUID();
        var original = owner.ensure(workspace.getId(), subject, AgentSandboxKind.CONVERSATION);
        Path copiedPerson = claims.copy(original.claimName());
        var online = service(SandboxOwnerFixtures.revocations(beans, owner));
        var request = Objects.requireNonNull(
                        transactions.execute(status -> online.preview(administratorId, personId, List.of())))
                .request();
        assertThat(Objects.requireNonNull(request.getSelectionsJson()))
                .contains("sandbox_ids", original.id().toString());
        assertThat(mapper.readTree(Objects.requireNonNull(request.getCountsJson()))
                        .path(MentorEntryRepository.COPIES_PERSON_DATA_STORE)
                        .asLong())
                .isEqualTo(1);
        transactions.executeWithoutResult(status -> online.requestErasure(request.getId(), administratorId, true));
        jdbc.update("DELETE FROM agent_sandbox WHERE id=? AND workspace_id=?", original.id(), original.workspaceId());
        assertThat(copiedPerson).exists();
        assertThat(claims.names()).contains(original.claimName());
        var offline = service(SandboxOwnerFixtures.revocations(beans, null));

        offline.run(request.getId());

        assertThat(offline.get(request.getId()).request().getState()).isEqualTo(PersonDataRequest.State.FAILED);
        assertNoAcknowledgement(request.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_sandbox WHERE id=? AND workspace_id=?",
                        Integer.class,
                        original.id(),
                        original.workspaceId()))
                .isZero();
        assertThat(copiedPerson).exists();
        assertThat(claims.names()).contains(original.claimName());
        assertThat(Objects.requireNonNull(offline.get(request.getId()).request().getSelectionsJson()))
                .contains(original.id().toString());
        var newer = owner.ensure(workspace.getId(), subject, AgentSandboxKind.CONVERSATION);
        Path newerCopy = claims.copy(newer.claimName());
        assertThat(newer.id()).isNotEqualTo(original.id());
        assertThat(newerCopy).exists();
        transactions.executeWithoutResult(status -> online.requestErasure(request.getId(), administratorId, true));

        online.run(request.getId());

        var completed = online.get(request.getId()).request();
        assertThat(completed.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(mapper.readTree(Objects.requireNonNull(completed.getCompletedJson()))
                        .path(MentorEntryRepository.COPIES_PERSON_DATA_STORE)
                        .path("count")
                        .asLong())
                .isEqualTo(1);
        assertThat(copiedPerson).doesNotExist();
        assertThat(newerCopy).doesNotExist();
        assertThat(claims.names()).doesNotContain(original.claimName(), newer.claimName());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_sandbox WHERE workspace_id=? AND id IN (?,?)",
                        Integer.class,
                        workspace.getId(),
                        original.id(),
                        newer.id()))
                .isZero();
    }

    private void assertSandbox(AgentSandboxes.LiveSandbox sandbox, String state) {
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM agent_sandbox WHERE id=? AND workspace_id=?",
                        String.class,
                        sandbox.id(),
                        sandbox.workspaceId()))
                .isEqualTo(state);
    }

    private void assertNoAcknowledgement(UUID requestId) {
        String completed = jdbc.queryForObject(
                "SELECT completed_json FROM person_data_request WHERE id=?", String.class, requestId);
        assertThat(mapper.readTree(Objects.requireNonNull(completed))
                        .has(MentorEntryRepository.COPIES_PERSON_DATA_STORE))
                .isFalse();
    }

    private PersonDataService service(ConversationSandboxCopies copies) {
        var catalog = new MentorPersonDataCatalog(
                beans.getBean(NamedParameterJdbcTemplate.class),
                mapper,
                beans.getBean(MentorRedaction.class),
                beans.getBeanProvider(PersonConversationCopySource.class)
                        .orderedStream()
                        .toList(),
                beans.getBean(MentorContextChanges.class),
                copies);
        var store = catalog.contributors().stream()
                .filter(contributor -> contributor.store().equals(MentorEntryRepository.COPIES_PERSON_DATA_STORE))
                .findFirst()
                .orElseThrow();
        return new PersonDataService(
                beans.getBean(PersonIdentityResolver.class),
                new PersonDataRegistry(List.of(new ToolCopyStore(List.of(store)))),
                List.of(),
                beans.getBean(PersonDataRequestRepository.class),
                beans.getBean(IdentityRestrictionService.class),
                beans.getBean(PersonDataWriteFence.class),
                beans.getBean(PersonDataCopyFence.class),
                beans.getBean(IssuedJwtRepository.class),
                beans.getBean(PlatformTransactionManager.class),
                mapper,
                jdbc,
                beans.getBean(MeterRegistry.class));
    }

    @PersonDataStores(MentorEntryRepository.COPIES_PERSON_DATA_STORE)
    private record ToolCopyStore(List<PersonDataContributor> contributors) implements PersonDataCatalog {}

    private static final class PhysicalClaims implements SandboxClaims {
        private final Path root;
        private boolean failDelete;

        private PhysicalClaims(Path root) {
            this.root = root;
        }

        Path copy(String name) {
            return root.resolve(name);
        }

        @Override
        public void create(Claim claim) {
            try {
                Files.writeString(copy(claim.name()), "person data in the sandbox");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void delete(String name) {
            if (failDelete) throw new IllegalStateException("Physical sandbox deletion failed");
            try {
                Files.deleteIfExists(copy(name));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public List<String> names() {
            try (var files = Files.list(root)) {
                return files.map(file -> file.getFileName().toString()).toList();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
