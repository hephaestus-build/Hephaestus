package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import de.tum.cit.aet.hephaestus.notification.ProductFeedbackEmailPreparation;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The notification module's two structural promises (ADR 0044): a notification is durable, and it
 * never reaches into another module's persistence.
 */
class NotificationArchitectureTest extends HephaestusArchitectureTest {

    private static final String DURABLE_LISTENERS_ONLY = "a plain listener loses the notification when the send fails; "
            + "only @ApplicationModuleListener records the attempt in event_publication and is redelivered";

    @Test
    void shouldHandleEveryNotificationEventThroughTheRegistry() {
        // ArchUnit matches direct annotations, so a method carrying @ApplicationModuleListener (which is
        // meta-annotated with @TransactionalEventListener) is not caught by either rule below.
        methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("..notification..")
                .and()
                .areAnnotatedWith(EventListener.class)
                .should()
                .beAnnotatedWith(ApplicationModuleListener.class)
                .because(DURABLE_LISTENERS_ONLY)
                .check(classes);

        methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("..notification..")
                .and()
                .areAnnotatedWith(TransactionalEventListener.class)
                .and()
                .areDeclaredInClassesThat()
                .doNotHaveFullyQualifiedName(ProductFeedbackEmailPreparation.class.getName())
                .and()
                .areDeclaredInClassesThat()
                .doNotHaveFullyQualifiedName("de.tum.cit.aet.hephaestus.notification.WorkspaceAlertEmailPreparation")
                .should()
                .beAnnotatedWith(ApplicationModuleListener.class)
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
