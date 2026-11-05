package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Repository methods that read every workspace and that only tests may call (ADR 0077). Each one carries a
 * {@code @WorkspaceAgnostic} that says so, and this rule keeps that reason true: no production class may call it.
 */
class TestOnlyRepositoryMethodsArchitectureTest extends HephaestusArchitectureTest {

    /** Add an entry when a conversion declares a test-only method on a repository. */
    private static final List<TestOnlyMethod> TEST_ONLY = List.of(
            new TestOnlyMethod(IssueRepository.class, "count"),
            new TestOnlyMethod(IssueRepository.class, "findAll"),
            new TestOnlyMethod(IssueRepository.class, "deleteAll"),
            new TestOnlyMethod(IssueCommentRepository.class, "count"),
            new TestOnlyMethod(IssueCommentRepository.class, "findAll"),
            new TestOnlyMethod(IssueCommentRepository.class, "deleteAll"),
            new TestOnlyMethod(PullRequestRepository.class, "count"));

    @Test
    void shouldCallNoTestOnlyRepositoryMethodFromProductionCode() {
        RULE.check(classes);
    }

    @Test
    void shouldNameDeclaredAndMarkedMethods() throws NoSuchMethodException {
        for (TestOnlyMethod entry : TEST_ONLY) {
            Method method = entry.repository().getDeclaredMethod(entry.name());
            assertThat(method.getAnnotation(WorkspaceAgnostic.class))
                    .as(
                            "%s.%s must carry a @WorkspaceAgnostic reason",
                            entry.repository().getSimpleName(), entry.name())
                    .isNotNull();
        }
    }

    @Test
    void shouldReportAProductionCallOfATestOnlyMethod() {
        var fixture = new ClassFileImporter().importClasses(CallsATestOnlyMethod.class);

        assertThat(RULE.evaluate(fixture).hasViolation()).isTrue();
    }

    private static final ArchRule RULE = noClasses()
            .should()
            .callMethodWhere(
                    new DescribedPredicate<JavaMethodCall>("call a repository method that only tests may call") {
                        @Override
                        public boolean test(JavaMethodCall call) {
                            // The bridge that javac adds for a narrowed return type is not a caller.
                            return !call.getOrigin().getModifiers().contains(JavaModifier.BRIDGE)
                                    && call.getTarget().getRawParameterTypes().isEmpty()
                                    && TEST_ONLY.stream()
                                            .anyMatch(entry ->
                                                    call.getTargetOwner().isEquivalentTo(entry.repository())
                                                            && call.getName().equals(entry.name()));
                        }
                    })
            .because(
                    "these methods read every workspace; production code reads through the monitored scope (ADR 0077)");

    private record TestOnlyMethod(Class<?> repository, String name) {}

    /** Stands for production code in {@link #shouldReportAProductionCallOfATestOnlyMethod}. */
    static final class CallsATestOnlyMethod {
        long countEverything(IssueRepository issues) {
            return issues.count();
        }
    }
}
