package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Object-mother for the owned JPA {@code @Entity} POJOs used across unit tests. Constructing the
 * real object (these all carry Lombok {@code @NoArgsConstructor + @Setter}) is cheaper and more
 * faithful than mocking it; see {@code NoMockingOwnedEntitiesTest} for the full rationale and the
 * guard that enforces it.
 *
 * <p>Each factory sets only the minimal identifying fields. Tests that need more should set
 * additional fields on the returned instance directly — the entity is fully mutable.
 */
public final class TestEntities {

    private static final AtomicLong VERSION_IDS = new AtomicLong(1);

    private TestEntities() {}

    /**
     * A {@link Repository} with id and {@code nameWithOwner} set.
     *
     * <p>Null arguments are skipped (the field's Lombok {@code @NonNull} setter rejects null);
     * leaving a field unset yields its natural default, which is what tests exercising the
     * "missing value" path want.
     */
    public static Repository repository(Long id, String nameWithOwner) {
        Repository repo = new Repository();
        repo.setId(id);
        if (nameWithOwner != null) {
            repo.setNameWithOwner(nameWithOwner);
            if (nameWithOwner.contains("/")) {
                repo.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
            }
        }
        return repo;
    }

    /** A {@link Repository} with id, {@code nameWithOwner} and {@code defaultBranch} set. */
    public static Repository repository(Long id, String nameWithOwner, @Nullable String defaultBranch) {
        Repository repo = repository(id, nameWithOwner);
        if (defaultBranch != null) {
            repo.setDefaultBranch(defaultBranch);
        }
        return repo;
    }

    /** An {@link Organization} with id and {@code login} set. */
    public static Organization organization(Long id, String login) {
        Organization org = new Organization();
        org.setId(id);
        org.setLogin(login);
        return org;
    }

    /** A {@link Commit} with id and {@code sha} set. */
    public static Commit commit(Long id, String sha) {
        Commit commit = new Commit();
        commit.setId(id);
        commit.setSha(sha);
        return commit;
    }

    /** A {@link PullRequest} with id, {@code number} and {@code title} set. */
    public static PullRequest pullRequest(Long id, int number, String title) {
        PullRequest pr = new PullRequest();
        pr.setId(id);
        pr.setNumber(number);
        pr.setTitle(title);
        return pr;
    }

    /** A {@link Workspace} with id set. */
    public static Workspace workspace(Long id) {
        Workspace ws = new Workspace();
        ws.setId(id);
        return ws;
    }

    /** A {@link Workspace} with id and {@code workspaceSlug} set. */
    public static Workspace workspace(Long id, String slug) {
        Workspace ws = workspace(id);
        ws.setWorkspaceSlug(slug);
        return ws;
    }

    /**
     * A persistable {@link Workspace} (no id; all NOT NULL columns populated) for integration tests
     * that save the entity. Status defaults to ACTIVE.
     */
    public static Workspace activeWorkspace(String slug) {
        Workspace ws = new Workspace();
        ws.setWorkspaceSlug(slug);
        ws.setDisplayName("Workspace " + slug);
        ws.setAccountLogin(slug + "-org");
        ws.setAccountType(AccountType.ORG);
        ws.setPublicActivityEnabled(true);
        ws.resume();
        return ws;
    }

    /** A {@link IdentityProvider} with id and {@code type} set. */
    public static IdentityProvider gitProvider(Long id, IdentityProviderType type) {
        IdentityProvider provider = new IdentityProvider();
        provider.setId(id);
        provider.setType(type);
        return provider;
    }

    public static ReviewJob reviewJob() {
        return new ReviewJob();
    }

    public static PracticeVersion practiceVersion() {
        return practiceVersion(
                VERSION_IDS.getAndIncrement(),
                1,
                JsonMapper.builder().build().valueToTree(DefinitionTestFixtures.definition()),
                "v6:" + "a".repeat(64));
    }

    /** Database-generated metadata is supplied explicitly for detached unit-test rows. */
    public static PracticeVersion practiceVersion(long id, int number, JsonNode definition, String digest) {
        var practice = new Practice();
        practice.setWorkspace(workspace(1L));
        var version = new PracticeVersion(practice, number, definition, null);
        ReflectionTestUtils.setField(version, "id", id);
        ReflectionTestUtils.setField(version, "name", definition.path("name").asString());
        ReflectionTestUtils.setField(version, "reviewDigest", digest);
        ReflectionTestUtils.setField(version, "createdAt", Instant.EPOCH);
        return version;
    }
}
