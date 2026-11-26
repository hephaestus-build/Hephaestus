package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.slack.api.methods.MethodsClient;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
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
    void shouldCallChatPostMessageOnlyFromPostAdmitted() {
        noClasses()
                .should()
                .accessTargetWhere(describe(
                        "call or reference chatPostMessage outside SlackMessageService.postAdmitted",
                        (JavaAccess<?> access) -> access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                                && target.getName().equals("chatPostMessage")
                                && target.getOwner().isAssignableTo(MethodsClient.class)
                                && !(access.getOriginOwner().isEquivalentTo(SlackMessageService.class)
                                        && access.getOrigin().getName().equals("postAdmitted"))))
                .because("each chat.postMessage request must pass the outbox fence at the HTTP write boundary")
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
