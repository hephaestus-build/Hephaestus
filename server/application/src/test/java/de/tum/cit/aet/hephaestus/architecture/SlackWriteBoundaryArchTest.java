package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.targetOwner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.slack.api.Slack;
import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.MethodsClient;
import com.slack.api.util.http.SlackHttpClient;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackInteractiveReplyAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import org.junit.jupiter.api.Test;

/** Enforces the effect boundary owned by {@code docs/contributor/outbox.mdx#effect-boundary}. */
class SlackWriteBoundaryArchTest extends HephaestusArchitectureTest {
    @Test
    void shouldDeliverDurableSlackWritesOnlyThroughOutboxAdapters() {
        noClasses()
                .that()
                .areNotAssignableTo(ActionAdapter.class)
                .should()
                .accessTargetWhere(target(owner(assignableTo(SlackMessageService.class)))
                        .and(target(name("postAdmitted").or(name("joinPublicChannel")))))
                .because("durable Slack effects need the outbox's idempotency, fence and retry state")
                .check(classes);
    }

    @Test
    void shouldCreateSlackClientsOnlyInTheSendFence() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendFence")
                .should()
                .accessTargetWhere(targetOwner(assignableTo(Slack.class).or(assignableTo(SlackHttpClient.class)))
                        .and(target(name("<init>").or(name("getInstance")).or(name("buildSlackHttpClient"))))
                        .or(targetOwner(assignableTo(MethodsClient.class).or(assignableTo(AsyncMethodsClient.class)))
                                .and(target(name("<init>")))))
                .because("Slack SDK clients must use the transport that checks admission at the HTTP write boundary")
                .check(classes);
    }

    @Test
    void shouldKeepDirectSlackRepliesInsideTheNamedInteractiveAdapter() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(SlackInteractiveReplyAdapter.class.getName())
                .should()
                .callMethodWhere(target(owner(assignableTo(SlackMessageService.class)))
                        .and(target(name("sendEphemeralForWorkspace")
                                .or(name("sendEphemeralForTeam"))
                                .or(name("publishHomeView")))))
                .because("the direct path is limited to replies to the acting user's own interaction")
                .check(classes);
    }
}
