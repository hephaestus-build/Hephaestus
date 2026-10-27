package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Team tables are global; workspace reads must scope organization by provider because SQL tenancy inspection cannot enforce it. */
class TeamOrganizationLookupArchitectureTest extends HephaestusArchitectureTest {

    /** Only the sync engines, which have already fixed the provider for the run and filter in-loop. */
    private static final Set<String> ALLOWED_CALLERS = Set.of(
            "de.tum.cit.aet.hephaestus.integration.scm.github.team.GitHubTeamSyncService",
            "de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService");

    @Test
    void shouldNameOnlyExistingClassesInAllowedCallers() {
        AllowlistEntries.assertEachNamesAClass("ALLOWED_CALLERS", ALLOWED_CALLERS, classes);
    }

    @Test
    @DisplayName("no new caller of TeamRepository.findAllByOrganizationIgnoreCase outside the sync engines")
    void noNewBareOrganizationEnumerationCallers() {
        ArchRule rule = noClasses()
                .that()
                .doNotHaveFullyQualifiedName("de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository")
                .and(new DescribedPredicate<>("are not allowlisted callers") {
                    @Override
                    public boolean test(JavaClass input) {
                        return !ALLOWED_CALLERS.contains(input.getFullName());
                    }
                })
                .should()
                .callMethod(TeamRepository.class, "findAllByOrganizationIgnoreCase", String.class)
                .because(
                        "the organization string is not a tenant boundary: two workspaces whose account_login "
                                + "collides on different providers (a GitHub org and a GitLab group of the same name) share "
                                + "it, so a bare enumeration returns the other tenant's teams. Workspace paths must scope by "
                                + "(organization, provider_id) — resolve a WorkspaceTeamScope and use the *AndProviderId finders.");
        rule.check(classes);
    }
}
