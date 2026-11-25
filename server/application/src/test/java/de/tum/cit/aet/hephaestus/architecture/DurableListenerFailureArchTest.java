package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.InstanceofCheck;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import de.tum.cit.aet.hephaestus.core.TransientFailures;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * A durable listener settles each domain failure that it can meet: it records the failure's stop reason and completes
 * its publication. A domain failure that escapes it repeats on every resubmission, so the publication uses all its
 * attempts and ends exhausted ({@code NotificationRedeliveryJob#MAX_ATTEMPTS}) instead of a recorded stop.
 *
 * <p>For each {@code @ApplicationModuleListener}, the test collects the project's runtime exceptions that the code it
 * calls can construct, and leaves out those that a {@code catch} in the listener's own class encloses, and the
 * transient ones that {@link TransientFailures} names. Each remaining exception must be in {@link #ESCAPES}, so a new
 * escape fails here, and a listener that no longer lets one escape must leave the inventory. The walk can find fewer of
 * the deepest calls in one build than in another, so an exception that a run does not find is no failure.
 *
 * <p>Limits: the walk follows direct calls, and an interface call to each production implementation, up to {@link
 * #DEPTH} calls deep. It cannot see a {@code catch} that rethrows, so it counts such a catch as settling. It proves no
 * behaviour: #5196 tracks a test per listener below that a permanent failure records its stop reason.
 */
class DurableListenerFailureArchTest extends HephaestusArchitectureTest {

    private static final int DEPTH = 8;

    /**
     * Listener ({@code Type.method(Parameter)}) to the domain exceptions that escape it today, and why. Each name is
     * qualified below {@code BASE_PACKAGE}, so two types with one simple name cannot share an entry. It may only
     * shrink: settle a failure in its listener and remove it here.
     */
    private static final Map<String, Escape> ESCAPES = Map.ofEntries(
            Map.entry(
                    "agent.conversation.turn.ConversationTurnCompletion.billTakenBack(conversation.ConversationLedgerEvents$AttemptTakenBack)",
                    new Escape(Set.of("core.exception.EntityNotFoundException"), "#5196: the turn is gone")),
            Map.entry(
                    "agent.conversation.turn.ConversationTurnCompletion.settle(agent.conversation.turn.ConversationReceiptsDue)",
                    new Escape(Set.of("core.exception.EntityNotFoundException"), "#5196: the turn is gone")),
            Map.entry(
                    "integration.scm.gitlab.workspace.GitLabConnectionStateListener.onDeactivated(integration.core.events.ConnectionLifecycleEvent$Deactivated)",
                    new Escape(
                            Set.of(
                                    "integration.core.connection.CredentialUnreadableException",
                                    "core.security.EncryptionException"),
                            "#5196: the stored token cannot be read")),
            Map.entry(
                    "practices.review.ItemEventReviewListener.on(integration.core.api.ItemEventRecorded)",
                    new Escape(
                            Set.of(
                                    "core.exception.EntityNotFoundException",
                                    "integration.scm.domain.workdir.GitRepositoryManager$GitOperationException",
                                    "core.exception.InvalidInputException",
                                    "integration.core.spi.ItemCaptureException",
                                    "agent.catalog.LlmModelUnavailableException",
                                    "integration.scm.domain.workdir.GitRepositoryManager$RepositoryFetchException",
                                    "practices.review.ReviewRequestRefused"),
                            "#5196: review request failures, and capture faults that #5190 retries while the occasion stays open")),
            Map.entry(
                    "practices.review.ItemEventReviewListener.on(integration.core.api.ItemSourceObserved)",
                    new Escape(
                            Set.of(
                                    "integration.core.spi.ItemCaptureException",
                                    "integration.scm.domain.workdir.GitRepositoryManager$RepositoryFetchException"),
                            "#5196: capture failures")),
            Map.entry(
                    "integration.core.connection.repair.ProviderResourceRepairDispatcher.on(integration.core.events.ConnectionCredentialsReplacedEvent)",
                    new Escape(
                            Set.of(
                                    "integration.core.connection.CredentialUnreadableException",
                                    "core.security.EncryptionException",
                                    "core.exception.InvalidInputException"),
                            "#5196: the stored credential cannot be read, or the input is invalid")),
            Map.entry(
                    "integration.core.connection.repair.ProviderResourceRepairDispatcher.on(integration.core.connection.repair.ProviderResourceRepairRequested)",
                    new Escape(
                            Set.of(
                                    "integration.core.connection.CredentialUnreadableException",
                                    "core.security.EncryptionException",
                                    "core.exception.InvalidInputException"),
                            "#5196: the stored credential cannot be read, or the input is invalid")),
            Map.entry(
                    "integration.core.connection.ProviderTeardown.on(integration.core.connection.ProviderTeardownRequested)",
                    new Escape(
                            Set.of(
                                    "integration.core.connection.CredentialUnreadableException",
                                    "core.security.EncryptionException",
                                    "integration.scm.domain.common.exception.InstallationNotFoundException",
                                    "core.exception.InvalidInputException",
                                    "integration.outline.client.OutlineApiException",
                                    "integration.outline.client.OutlineRateLimitedException",
                                    "integration.slack.connect.SlackOAuthException"),
                            "#5196: provider failures outside the recorded removal")),
            Map.entry(
                    "practices.review.ReviewCommandListener.on(integration.core.events.BotCommandReceivedEvent)",
                    new Escape(
                            Set.of(
                                    "integration.core.connection.CredentialUnreadableException",
                                    "integration.core.spi.FeedbackDeliveryException",
                                    "integration.scm.domain.workdir.GitRepositoryManager$GitOperationException",
                                    "integration.scm.domain.common.exception.InstallationNotFoundException",
                                    "integration.scm.domain.common.exception.InstallationSuspendedException",
                                    "core.exception.InvalidInputException",
                                    "agent.catalog.LlmModelUnavailableException",
                                    "integration.core.egress.OutboundEgressSuppressedException",
                                    "integration.scm.domain.workdir.GitRepositoryManager$RepositoryFetchException",
                                    "practices.review.ReviewRequestRefused"),
                            "#5196: the reply or the requested review cannot proceed")),
            Map.entry(
                    "practices.review.ReviewCommandListener.on(practices.review.CommentRequestedReviewEnded)",
                    new Escape(
                            Set.of(
                                    "integration.core.connection.CredentialUnreadableException",
                                    "integration.core.spi.FeedbackDeliveryException",
                                    "integration.scm.domain.common.exception.InstallationNotFoundException",
                                    "integration.scm.domain.common.exception.InstallationSuspendedException",
                                    "integration.core.egress.OutboundEgressSuppressedException"),
                            "#5196: the reply cannot be sent")),
            Map.entry(
                    "agent.job.ReviewJobCompletionService.cancel(practices.spi.ReviewCancellationRequested)",
                    new Escape(
                            Set.of(
                                    "core.exception.EntityNotFoundException",
                                    "agent.job.ReviewJobStateConflictException"),
                            "#5196: the job is gone or in another state")),
            Map.entry(
                    "integration.slack.interactivity.SlackInteractivityHandler.onOptOutConfirmation(integration.slack.interactivity.SlackInteractivityHandler$OptOutConfirmation)",
                    new Escape(
                            Set.of("core.exception.JournalPendingException"),
                            "#5196: the privacy journal is not ready")));

    private record Escape(Set<String> exceptions, String reason) {}

    @Test
    void shouldLetNoDomainFailureEscapeADurableListenerBeyondTheInventory() {
        Set<JavaClass> transientTypes = transientTypes();
        Map<String, Set<String>> found = new TreeMap<>();
        for (JavaMethod listener : durableListeners()) {
            var walk = new Walk(listener.getOwner(), transientTypes);
            Set<String> escaping = walk.escapes(listener, Set.of(), 0).stream()
                    .map(DurableListenerFailureArchTest::name)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!escaping.isEmpty()) found.put(id(listener), escaping);
        }

        ESCAPES.forEach(
                (listener, escape) -> assertThat(escape.reason()).as(listener).isNotBlank());
        Map<String, Set<String>> unlisted = new TreeMap<>();
        found.forEach((listener, escaping) -> {
            var listed = ESCAPES.containsKey(listener) ? ESCAPES.get(listener).exceptions() : Set.<String>of();
            var extra = escaping.stream()
                    .filter(type -> !listed.contains(type))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!extra.isEmpty()) unlisted.put(listener, extra);
        });
        assertThat(unlisted).as("""
                        These domain failures escape durable listeners, and ESCAPES does not list them. Settle each one \
                        in its listener: record its stop reason and complete the publication. List one only for a \
                        failure that is transient by design, and say why.""").isEmpty();
        assertThat(ESCAPES.keySet().stream()
                        .filter(listener -> !found.containsKey(listener))
                        .toList())
                .as("These listeners let no domain failure escape any more: remove them from ESCAPES")
                .isEmpty();
    }

    private static List<JavaMethod> durableListeners() {
        return classes.stream()
                .flatMap(owner -> owner.getMethods().stream())
                .filter(method ->
                        AnnotatedElementUtils.hasAnnotation(method.reflect(), ApplicationModuleListener.class))
                .toList();
    }

    /** The project's types that {@link TransientFailures} counts as transient: a retry is right for them. */
    private static Set<JavaClass> transientTypes() {
        return classes.get(TransientFailures.class).getCodeUnits().stream()
                .flatMap(unit -> unit.getInstanceofChecks().stream())
                .map(InstanceofCheck::getRawType)
                .filter(type -> type.getPackageName().startsWith(BASE_PACKAGE))
                .collect(Collectors.toSet());
    }

    /** {@code Type.method(Parameter)}: overloads of one listener method are separate listeners. */
    private static String id(JavaMethod method) {
        return name(method.getOwner()) + "." + method.getName()
                + method.getRawParameterTypes().stream()
                        .map(DurableListenerFailureArchTest::name)
                        .collect(Collectors.joining(",", "(", ")"));
    }

    /** The binary name, below {@code BASE_PACKAGE} for a project type. */
    private static String name(JavaClass type) {
        String prefix = BASE_PACKAGE + ".";
        return type.getName().startsWith(prefix) ? type.getName().substring(prefix.length()) : type.getName();
    }

    /** One listener's walk: catches count only inside the listener's own class, the boundary that settles. */
    private static final class Walk {
        private final JavaClass boundary;
        private final Set<JavaClass> transientTypes;
        private final Map<String, Set<JavaClass>> raised = new HashMap<>();

        Walk(JavaClass boundary, Set<JavaClass> transientTypes) {
            this.boundary = boundary;
            this.transientTypes = transientTypes;
        }

        /** What escapes {@code unit} of the listener's class, given the types that enclosing catches settle. */
        Set<JavaClass> escapes(JavaCodeUnit unit, Set<JavaClass> settled, int depth) {
            Set<JavaClass> escaping = new HashSet<>();
            for (JavaAccess<?> access : unit.getAccessesFromSelf()) {
                if (!(access instanceof JavaCall<?> call)) continue;
                Set<JavaClass> caught = new HashSet<>(settled);
                caught.addAll(caughtAround(unit, access));
                JavaClass constructed = call.getTargetOwner();
                if (call.getName().equals("<init>") && isDomainFailure(constructed) && !settledBy(constructed, caught))
                    escaping.add(constructed);
                for (JavaCodeUnit target : targets(call)) {
                    Set<JavaClass> below = target.getOwner().equals(boundary) && depth < DEPTH
                            ? escapes(target, caught, depth + 1)
                            : raisedBy(target, depth + 1);
                    below.stream().filter(type -> !settledBy(type, caught)).forEach(escaping::add);
                }
            }
            return escaping;
        }

        /** The domain exceptions that {@code unit} and the code it calls can construct, whatever catches them. */
        private Set<JavaClass> raisedBy(JavaCodeUnit unit, int depth) {
            // Breadth first, by levels: each unit is read at its shortest distance, so the result does not depend on
            // the
            // order in which a set yields the calls, as a depth-first walk with a cycle guard would.
            String key = unit.getFullName() + "@" + depth;
            Set<JavaClass> known = raised.get(key);
            if (known != null) return known;
            Set<JavaClass> found = new HashSet<>();
            Set<JavaCodeUnit> seen = new HashSet<>(Set.of(unit));
            Set<JavaCodeUnit> level = Set.of(unit);
            for (int at = depth; at <= DEPTH && !level.isEmpty(); at++) {
                Set<JavaCodeUnit> next = new HashSet<>();
                for (JavaCodeUnit current : level) {
                    for (JavaAccess<?> access : current.getAccessesFromSelf()) {
                        if (!(access instanceof JavaCall<?> call)) continue;
                        JavaClass owner = call.getTargetOwner();
                        if (call.getName().equals("<init>") && isDomainFailure(owner)) found.add(owner);
                        for (JavaCodeUnit target : targets(call)) if (seen.add(target)) next.add(target);
                    }
                }
                level = next;
            }
            raised.put(key, found);
            return found;
        }

        private boolean isDomainFailure(JavaClass type) {
            return type.isAssignableTo(RuntimeException.class)
                    && type.getPackageName().startsWith(BASE_PACKAGE)
                    && inProduction(type)
                    && transientTypes.stream().noneMatch(other -> type.isAssignableTo(other.getName()));
        }

        private static Set<JavaClass> caughtAround(JavaCodeUnit unit, JavaAccess<?> access) {
            return unit.getTryCatchBlocks().stream()
                    .filter(block -> block.getAccessesContainedInTryBlock().contains(access))
                    .map(TryCatchBlock::getCaughtThrowables)
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet());
        }

        private static boolean settledBy(JavaClass type, Set<JavaClass> caught) {
            return caught.stream().anyMatch(other -> type.isAssignableTo(other.getName()));
        }

        /** The production code that a call can run: its target, and each implementation of an interface target. */
        private static Set<JavaCodeUnit> targets(JavaCall<?> call) {
            JavaClass owner = call.getTargetOwner();
            if (!owner.getPackageName().startsWith(BASE_PACKAGE)) return Set.of();
            Set<JavaCodeUnit> found = new HashSet<>();
            call.getTarget()
                    .resolveMember()
                    .filter(member -> inProduction(member.getOwner()))
                    .ifPresent(found::add);
            if (owner.isInterface() || owner.getModifiers().contains(JavaModifier.ABSTRACT)) {
                String[] parameters = call.getTarget().getRawParameterTypes().stream()
                        .map(JavaClass::getName)
                        .toArray(String[]::new);
                owner.getAllSubclasses().stream()
                        .filter(HephaestusArchitectureTest::inProduction)
                        .map(sub -> sub.tryGetMethod(call.getName(), parameters))
                        .flatMap(Optional::stream)
                        .forEach(found::add);
            }
            return found;
        }
    }
}
