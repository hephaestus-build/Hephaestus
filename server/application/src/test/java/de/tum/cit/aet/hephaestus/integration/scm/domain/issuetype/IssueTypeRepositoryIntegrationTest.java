package de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * A GitLab subgroup is its own organization without issue types of its own, so an issue in it takes the type of its
 * nearest parent group on the same instance: never a sibling group's, and never another instance's.
 */
@Transactional
class IssueTypeRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IssueTypeRepository issueTypeRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    private IdentityProvider gitlabProvider;
    private IdentityProvider otherProvider;
    private Organization rootOrg;
    private Organization subgroupOrg;

    @BeforeEach
    void setUp() {
        gitlabProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.example.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com")));

        otherProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://other.gitlab.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://other.gitlab.com")));

        rootOrg = persistOrg(gitlabProvider, 1000L, "root-group");
        subgroupOrg = persistOrg(gitlabProvider, 1001L, "root-group/subgroup");
    }

    private Organization persistOrg(IdentityProvider provider, long nativeId, String login) {
        Organization org = new Organization();
        org.setNativeId(nativeId);
        org.setLogin(login);
        org.setHtmlUrl("https://gitlab.example.com/" + login);
        org.setProvider(provider);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        return organizationRepository.save(org);
    }

    private IssueType persistIssueType(String id, String name, Organization org) {
        IssueType type = new IssueType();
        type.setNativeId(id);
        type.setName(name);
        type.setColor(IssueType.Color.BLUE);
        type.setEnabled(true);
        type.setOrganization(org);
        return issueTypeRepository.save(type);
    }

    @Test
    void shouldResolveTheRootGroupsTypeForASubgroupWithoutTypes() {
        persistIssueType("gid://gitlab/WorkItems::Type/1", "Issue", rootOrg);

        Optional<IssueType> resolved = issueTypeRepository.findInNearestParentGroupByName(id(subgroupOrg), "Issue");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getNativeId()).isEqualTo("gid://gitlab/WorkItems::Type/1");
        assertThat(resolved.get().getOrganization().getId()).isEqualTo(rootOrg.getId());
    }

    @Test
    void shouldMatchTheNameCaseInsensitively() {
        persistIssueType("gid://gitlab/WorkItems::Type/2", "Task", rootOrg);

        assertThat(issueTypeRepository.findInNearestParentGroupByName(id(subgroupOrg), "task"))
                .isPresent();
        assertThat(issueTypeRepository.findInNearestParentGroupByName(id(subgroupOrg), "TASK"))
                .isPresent();
    }

    @Test
    void shouldNotTakeTheTypeOfAGroupWithTheSamePathOnAnotherInstance() {
        Organization twin = persistOrg(otherProvider, 2000L, "root-group");
        persistIssueType("gid://gitlab/WorkItems::Type/1", "Bug", twin);

        assertThat(issueTypeRepository.findInNearestParentGroupByName(id(subgroupOrg), "Bug"))
                .isEmpty();
    }

    @Test
    void shouldNotTakeTheTypeOfASiblingOrPrefixGroup() {
        // Created before root-group's own type, so a lowest-id rule would pick these.
        persistIssueType("gid://gitlab/WorkItems::Type/4", "Epic", persistOrg(gitlabProvider, 1002L, "another-group"));
        persistIssueType("gid://gitlab/WorkItems::Type/4", "Epic", persistOrg(gitlabProvider, 1003L, "root-groupx"));

        assertThat(issueTypeRepository.findInNearestParentGroupByName(id(subgroupOrg), "Epic"))
                .isEmpty();
    }

    @Test
    void shouldSkipDisabledTypes() {
        IssueType disabled = persistIssueType("gid://gitlab/WorkItems::Type/3", "Incident", rootOrg);
        disabled.setEnabled(false);
        issueTypeRepository.save(disabled);

        assertThat(issueTypeRepository.findInNearestParentGroupByName(id(subgroupOrg), "Incident"))
                .isEmpty();
    }

    @Test
    void shouldPreferTheNearestParentGroup() {
        Organization deeper = persistOrg(gitlabProvider, 1004L, "root-group/subgroup/deeper");
        persistIssueType("gid://gitlab/WorkItems::Type/10", "Epic", rootOrg);
        IssueType nearer = persistIssueType("gid://gitlab/WorkItems::Type/10", "Epic", subgroupOrg);

        Optional<IssueType> resolved = issueTypeRepository.findInNearestParentGroupByName(id(deeper), "Epic");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getId()).isEqualTo(nearer.getId());
    }

    private static long id(Organization organization) {
        Long id = organization.getId();
        assertNotNull(id);
        return id;
    }
}
