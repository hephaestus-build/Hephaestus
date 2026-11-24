package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.AnotherWorkspaceFilterFixture;
import de.tum.cit.aet.hephaestus.workspace.context.PersonalAllowed;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextFilter;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import org.junit.jupiter.api.Test;

/**
 * Every workspace-scoped controller says whether a personal space reaches it (KR-06): {@code @TeamOnly}, or
 * {@code @PersonalAllowed} after a deliberate choice. A controller that says neither would silently be team-only, and
 * one that says both would leave the reader to guess.
 *
 * <p>Only the request filter resolves a slug to a workspace of any kind, and it admits only the owner to a personal
 * space. Every other slug lookup finds team workspaces only, so a new path into a workspace cannot reach a personal
 * space by accident.
 */
class WorkspaceKindArchitectureTest extends HephaestusArchitectureTest {

    static final ArchRule ONE_WORKSPACE_KIND = classes()
            .that()
            .areMetaAnnotatedWith(WorkspaceScopedController.class)
            .should(new ArchCondition<JavaClass>("declare exactly one of @TeamOnly and @PersonalAllowed") {
                @Override
                public void check(JavaClass controller, ConditionEvents events) {
                    boolean team = controller.isMetaAnnotatedWith(TeamOnly.class);
                    boolean personal = controller.isMetaAnnotatedWith(PersonalAllowed.class);
                    if (team == personal) {
                        events.add(SimpleConditionEvent.violated(
                                controller, controller.getName() + (team ? " declares both" : " declares neither")));
                    }
                }
            })
            .because("a personal space reaches a workspace route only by a deliberate @PersonalAllowed");

    private static final DescribedPredicate<JavaAccess<?>> ANY_KIND_SLUG_LOOKUP = DescribedPredicate.describe(
            "a lookup of a workspace of any kind by slug",
            access -> access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                    && target.getName().equals("findAnyKindByWorkspaceSlug")
                    && target.getOwner().isAssignableTo(WorkspaceRepository.class));

    /** The filter that checks the owner of a personal space before it uses the workspace it found. */
    static final ArchRule ONLY_THE_REQUEST_FILTER_FINDS_ANY_KIND = noClasses()
            .that()
            .doNotBelongToAnyOf(WorkspaceContextFilter.class)
            .should()
            .accessTargetWhere(ANY_KIND_SLUG_LOOKUP)
            .because(
                    "only the request filter admits a person to their personal space by slug; every other path looks up"
                            + " team workspaces");

    @Test
    void shouldLetOnlyTheRequestFilterFindAWorkspaceOfAnyKindBySlug() {
        ONLY_THE_REQUEST_FILTER_FINDS_ANY_KIND.check(classes);
    }

    @Test
    void shouldReportAnotherClassThatFindsAWorkspaceOfAnyKindBySlug() {
        JavaClasses fixtures = classesWithTests.that(DescribedPredicate.describe(
                "the any-kind lookup fixtures",
                type -> type.getName().equals(WorkspaceKindViolationFixtures.AnyKindSlugReader.class.getName())
                        || type.getName().equals(AnotherWorkspaceFilterFixture.class.getName())));
        assertThat(fixtures).hasSize(2);

        assertThatThrownBy(() -> ONLY_THE_REQUEST_FILTER_FINDS_ANY_KIND.check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AnyKindSlugReader")
                .hasMessageContaining("AnotherWorkspaceFilterFixture");
    }

    @Test
    void shouldDeclareOneWorkspaceKindOnEveryWorkspaceController() {
        ONE_WORKSPACE_KIND.check(classes);
    }

    @Test
    void shouldReportAControllerThatDeclaresNeitherOrBoth() {
        JavaClasses fixtures = classesWithTests.that(DescribedPredicate.describe(
                "the workspace kind fixtures",
                type -> type.getName().startsWith(WorkspaceKindViolationFixtures.class.getName() + "$")
                        && type.isMetaAnnotatedWith(WorkspaceScopedController.class)));
        assertThat(fixtures).hasSize(2);

        assertThatThrownBy(() -> ONE_WORKSPACE_KIND.check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("WorkspaceKindViolationFixtures$Neither declares neither")
                .hasMessageContaining("WorkspaceKindViolationFixtures$Both declares both");
    }
}
