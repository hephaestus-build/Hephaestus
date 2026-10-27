package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The notification module's two structural promises (ADR 0044): a notification is durable, and it
 * never reaches into another module's persistence.
 */
class NotificationArchitectureTest extends HephaestusArchitectureTest {

    private static final String DURABLE_LISTENERS_ONLY = "a plain listener loses the notification when the send fails; "
            + "only @ApplicationModuleListener records the attempt in event_publication and is redelivered";

    @Test
    void shouldNotUsePlainEventListeners() {
        // ArchUnit matches direct annotations, so a method carrying @ApplicationModuleListener (which is
        // meta-annotated with @TransactionalEventListener) passes this rule and the next.
        noMethods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("..notification..")
                .should()
                .beAnnotatedWith(EventListener.class)
                .because(DURABLE_LISTENERS_ONLY)
                .check(classes);
    }

    @Test
    void shouldRunPlainTransactionalListenersOnlyBeforeCommit() {
        // A BEFORE_COMMIT listener runs inside the publishing transaction, so what it publishes commits with the
        // event or not at all.
        noMethods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("..notification..")
                .should()
                .beAnnotatedWith(DescribedPredicate.describe(
                        "@TransactionalEventListener outside BEFORE_COMMIT",
                        annotation -> annotation.getRawType().isEquivalentTo(TransactionalEventListener.class)
                                && annotation
                                        .get("phase")
                                        .map(phase -> !((JavaEnumConstant) phase)
                                                .name()
                                                .equals("BEFORE_COMMIT"))
                                        .orElse(true)))
                .because(DURABLE_LISTENERS_ONLY)
                .check(classes);
    }

    @Test
    void shouldKeepPersistenceOutOfEmailTransport() {
        noClasses()
                .that()
                .resideInAPackage("..notification..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("de.tum.cit.aet.hephaestus..domain..")
                .because("account and provider entities remain behind their owning modules' query ports")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..notification.email..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..domain..", "jakarta.persistence..", "org.springframework.data..")
                .because("templates render on an async thread after the publishing transaction closed; an entity "
                        + "there is a LazyInitializationException waiting to happen, and an address belongs in the "
                        + "event or behind the SPI")
                .check(classes);
    }
}
