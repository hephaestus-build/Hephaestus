package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceControlContract;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmPullRequestItems;
import org.junit.jupiter.api.Test;

/**
 * The mentoring modules speak about people, records and threads, never about source control or one provider (ADR
 * 0138). They reach a provider only through the neutral ports of {@code integration.core}, and the adapters translate.
 */
class DomainNeutralityArchTest extends HephaestusArchitectureTest {
    /** Source-control shapes that sit in the shared integration kernel, outside the adapter packages. */
    private static final DescribedPredicate<JavaClass> SOURCE_CONTROL_CONTRACTS = DescribedPredicate.describe(
            "source-control contracts of the shared integration kernel",
            type -> type.isAnnotatedWith(SourceControlContract.class));

    @Test
    void shouldKeepTheMentoringModulesFreeOfSourceControlAndProviderAdapters() {
        neutrality(DescribedPredicate.describe(
                        "mentoring classes", type -> MentoringModules.holds(type.getPackageName())))
                .check(classes);
    }

    @Test
    void shouldRefuseAProviderAdapterAndASourceControlContractOfTheSharedKernel() {
        var fixtures = DescribedPredicate.<JavaClass>describe(
                "neutrality fixtures",
                type -> type.getName().startsWith(DomainNeutralityArchTest.class.getName() + "$"));
        assertThat(neutrality(fixtures)
                        .evaluate(classesWithTests)
                        .getFailureReport()
                        .getDetails())
                .anySatisfy(detail -> assertThat(detail).contains("UsesAReviewCommand", "BotCommandReceivedEvent"))
                .anySatisfy(detail -> assertThat(detail).contains("UsesANewContract", "NewSourceControlContract"))
                .anySatisfy(detail -> assertThat(detail).contains("UsesAnAdapter", "ScmPullRequestItems"))
                .noneSatisfy(detail -> assertThat(detail).contains("NeutralClass"));
    }

    private static ArchRule neutrality(DescribedPredicate<JavaClass> subjects) {
        return noClasses()
                .that(subjects)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        BASE_PACKAGE + ".integration.scm..",
                        BASE_PACKAGE + ".integration.slack..",
                        BASE_PACKAGE + ".integration.outline..",
                        "org.kohsuke.github..",
                        "org.gitlab4j..",
                        "com.slack.api..")
                .orShould()
                .dependOnClassesThat(SOURCE_CONTROL_CONTRACTS)
                .because("an adapter maps its provider onto the neutral ports, so a mentoring module names no provider")
                .allowEmptyShould(true);
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class UsesAReviewCommand {
        long item(BotCommandReceivedEvent command) {
            return command.itemId();
        }
    }

    @SourceControlContract
    private record NewSourceControlContract(long mergeRequestId) {}

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class UsesANewContract {
        long id(NewSourceControlContract contract) {
            return contract.mergeRequestId();
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class UsesAnAdapter {
        Object items(ScmPullRequestItems items) {
            return items;
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class NeutralClass {
        long id() {
            return 1L;
        }
    }
}
