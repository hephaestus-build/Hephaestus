package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackInteractiveReplyAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import org.junit.jupiter.api.Test;

/** Enforces the effect boundary owned by {@code docs/contributor/outbox.md#effect-boundary}. */
class SlackWriteBoundaryArchTest extends HephaestusArchitectureTest {
    @Test
    void shouldDeliverDurableSlackWritesOnlyThroughOutboxAdapters() {
        noClasses()
                .that()
                .areNotAssignableTo(ActionAdapter.class)
                .should()
                .callMethodWhere(target(owner(assignableTo(SlackMessageService.class)))
                        .and(target(name("postMessage").or(name("joinPublicChannel")))))
                .because("durable Slack effects need the outbox's idempotency, fence and retry state")
                .check(classes);
    }

    @Test
    void shouldKeepDirectSlackRepliesInsideTheNamedInteractiveAdapter() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(SlackInteractiveReplyAdapter.class.getName())
                .should()
                .callMethodWhere(target(owner(assignableTo(SlackMessageService.class)))
                        .and(target(name("sendEphemeralForWorkspace").or(name("publishHomeView")))))
                .because("the direct path is limited to replies to the acting user's own interaction")
                .check(classes);
    }
}
