package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the set of classes allowed to call {@code WorkspaceRepository.findByAccountLoginIgnoreCase}
 * directly. New callers must use a kind-aware lookup (e.g. {@code ConnectionService.findActive(workspaceId, kind)})
 * to avoid false-collapse across vendors that share the same account-login string.
 */
class AccountLoginLookupArchitectureTest extends HephaestusArchitectureTest {

    private static final Set<String> ALLOWED_CALLERS = Set.of(
            "de.tum.cit.aet.hephaestus.workspace.WorkspaceQueryService",
            "de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver",
            "de.tum.cit.aet.hephaestus.workspace.WorkspaceProvisioningService",
            "de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceContextResolverAdapter",
            "de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceOrganizationMembershipAdapter",
            "de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener");

    @Test
    @DisplayName("no new caller of WorkspaceRepository.findByAccountLoginIgnoreCase outside the allowlist")
    void noNewBareLoginLookupCallers() {
        ArchRule rule = noClasses()
                .that()
                .doNotHaveFullyQualifiedName("de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository")
                .and(new DescribedPredicate<>("are not allowlisted callers") {
                    @Override
                    public boolean test(JavaClass input) {
                        return !ALLOWED_CALLERS.contains(input.getFullName());
                    }
                })
                .should()
                .callMethod(WorkspaceRepository.class, "findByAccountLoginIgnoreCase", String.class)
                .because("bare account-login lookups false-collapse workspaces that share the same login across "
                        + "different SCM vendors (e.g. GitHub org and GitLab group with identical name). "
                        + "New code must use a kind-aware lookup via ConnectionService.findActive(workspaceId, kind) "
                        + "or scope by the active provider kind.");
        rule.check(classes);
    }
}
