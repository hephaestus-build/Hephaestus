package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceService;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceRootsAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A workspace is created in one place (ADR 0099, rule 4): {@link Workspace#create} sets what every new workspace starts
 * with, and no production class outside {@link Workspace} calls its constructor.
 */
class WorkspaceCreationArchTest extends HephaestusArchitectureTest {

    @Test
    void shouldConstructAWorkspaceOnlyInsideWorkspace() {
        assertThat(constructions(classes)).isEmpty();
    }

    @Test
    void shouldCreateEveryWorkspaceThroughTheFactory() {
        // The two entry points: a person or an admin creates a workspace, and a source provider's installation creates
        // one.
        assertThat(List.of(WorkspaceService.class, WorkspaceRootsAdapter.class))
                .allSatisfy(entry -> assertThat(classes.get(entry).getMethodCallsFromSelf())
                        .as("%s calls Workspace.create", entry.getSimpleName())
                        .anyMatch(call -> call.getTargetOwner().isEquivalentTo(Workspace.class)
                                && call.getName().equals("create")));
    }

    @Test
    void shouldFindAConstructionOutsideWorkspace() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(Workspace.class, Builds.class);

        assertThat(constructions(fixtures)).singleElement().asString().contains(Builds.class.getName());
    }

    private static List<String> constructions(JavaClasses types) {
        return types.stream()
                .filter(type -> !type.isEquivalentTo(Workspace.class))
                .flatMap(type -> type.getConstructorCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().isEquivalentTo(Workspace.class))
                .map(call -> call.getOrigin().getFullName() + " constructs a Workspace; use Workspace.create "
                        + call.getSourceCodeLocation())
                .toList();
    }

    static class Builds {
        Workspace workspace() {
            var workspace = new Workspace();
            workspace.setAccountType(AccountType.ORG);
            return workspace;
        }
    }
}
