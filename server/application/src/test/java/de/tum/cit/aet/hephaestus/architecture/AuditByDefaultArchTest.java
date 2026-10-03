package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every admin mutation endpoint must declare whether it is audited: {@link Audited} names the ledger
 * the change lands in, {@link AuditExempt} says why it deliberately lands in none. An endpoint that
 * declares neither is how a trail develops a hole nobody chose.
 */
class AuditByDefaultArchTest extends HephaestusArchitectureTest {

    private static final String INSTANCE_ADMIN_AUTHORITY = "app_admin";

    @Test
    void everyAdminMutationEndpointDeclaresItsAuditStatus() {
        List<String> undeclared = classes.stream()
                .filter(AuditByDefaultArchTest::isController)
                .flatMap(c -> c.getMethods().stream())
                .filter(AuditByDefaultArchTest::isMutation)
                .filter(AuditByDefaultArchTest::isAdminGated)
                .filter(m -> !m.isAnnotatedWith(Audited.class) && !m.isAnnotatedWith(AuditExempt.class))
                .map(m -> m.getOwner().getSimpleName() + "." + m.getName())
                .sorted()
                .toList();

        assertThat(undeclared).as("""
                These admin mutation endpoints declare neither @Audited nor @AuditExempt. Decide: if the \
                action changes configuration or access, record it on the audit trail and mark it \
                @Audited(ledger = …, type = "…"); if it genuinely should not be recorded, mark it \
                @AuditExempt(reason="…"). An undeclared admin action is how an audit trail silently \
                develops holes.""").isEmpty();
    }

    /**
     * {@link Audited#type()} is a bare token, because no annotation member can have a type that depends
     * on another member's value — so the compiler cannot check it. A typo would be read as "some row type
     * we don't know", silently opting the endpoint out of {@link #auditDeclarationsMatchTheCallGraph()}.
     */
    @Test
    void everyAuditedTypeIsAConstantOfItsLedgersVocabulary() {
        List<String> malformed = classes.stream()
                .filter(AuditByDefaultArchTest::isController)
                .flatMap(c -> c.getMethods().stream())
                .flatMap(m -> m.tryGetAnnotationOfType(Audited.class).stream()
                        .flatMap(a -> malformedReason(a.ledger(), a.type())
                                .map(reason -> m.getOwner().getSimpleName() + "." + m.getName() + ": " + reason)
                                .stream()))
                .sorted()
                .toList();

        assertThat(malformed).as("""
                These @Audited declarations name a row type their ledger does not have. `type` must be a \
                constant of the ledger's vocabulary (see AuditLedger), and is left off only for a ledger \
                that has no vocabulary at all.""").isEmpty();
    }

    private static Optional<String> malformedReason(AuditLedger ledger, String type) {
        Set<String> vocabulary = ledger.vocabulary();
        if (type.isEmpty()) {
            return vocabulary.isEmpty()
                    ? Optional.empty()
                    : Optional.of(ledger + " rows are typed — name the constant, e.g. type = \"X\"");
        }
        if (vocabulary.isEmpty()) {
            return Optional.of(ledger + " types its rows with a free string, so '" + type + "' names nothing");
        }
        return vocabulary.contains(type)
                ? Optional.empty()
                : Optional.of("'" + type + "' is not a constant of the " + ledger + " vocabulary");
    }

    /**
     * An {@code @Audited} endpoint that reaches no recorder is a promise nothing keeps; an
     * {@code @AuditExempt} one that records anyway invites the next reader to delete a working audit
     * call. Neither is visible in review: the annotation and the call site sit in different files.
     *
     * <p>Only {@code config_audit} values are checked — the other ledgers point outside
     * {@link ConfigAuditPort} and cannot be resolved from the call graph.
     */
    @Test
    void auditDeclarationsMatchTheCallGraph() {
        List<String> contradictions = classes.stream()
                .filter(AuditByDefaultArchTest::isController)
                .flatMap(c -> c.getMethods().stream())
                .map(AuditByDefaultArchTest::contradiction)
                .flatMap(Optional::stream)
                .sorted()
                .toList();

        assertThat(contradictions).as("""
                These endpoints' audit declarations contradict what they actually do. Either wire the \
                producer, or change the declaration to say what is true.""").isEmpty();
    }

    private static Optional<String> contradiction(JavaMethod method) {
        String name = method.getOwner().getSimpleName() + "." + method.getName();
        boolean records = reachesRecorder(method, new HashSet<>(), 0);
        if (method.isAnnotatedWith(AuditExempt.class) && records) {
            return Optional.of(name + " is @AuditExempt but reaches ConfigAuditPort.record");
        }
        boolean namesEntityType = method.tryGetAnnotationOfType(Audited.class)
                .map(a -> a.ledger() == AuditLedger.CONFIG_AUDIT)
                .orElse(false);
        if (namesEntityType && !records) {
            return Optional.of(name + " is @Audited but reaches no recorder");
        }
        return Optional.empty();
    }

    /** Depth-bounded so a cyclic service graph terminates; audit writes sit shallow behind a handler. */
    private static boolean reachesRecorder(JavaMethod method, Set<String> seen, int depth) {
        // Keyed on (method, depth): a method first reached at the depth limit must not cache a `false`
        // that short-circuits a shallower path to a real recorder.
        if (depth > MAX_CALL_DEPTH || !seen.add(method.getFullName() + "@" + depth)) {
            return false;
        }
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            JavaClass target = call.getTargetOwner();
            if (target.getName().equals(ConfigAuditPort.class.getName())
                    && call.getName().equals("record")) {
                return true;
            }
            if (!target.getPackageName().startsWith("de.tum.cit.aet.hephaestus")) {
                continue;
            }
            for (JavaMethod resolved : call.getTarget().resolveMember().stream().toList()) {
                if (reachesRecorder(resolved, seen, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final int MAX_CALL_DEPTH = 6;

    static boolean isController(JavaClass clazz) {
        // Meta-annotated too: @WorkspaceScopedController composes @RestController, and every
        // workspace-admin surface uses it.
        return (clazz.isAnnotatedWith(RestController.class) || clazz.isMetaAnnotatedWith(RestController.class));
    }

    static boolean isMutation(JavaMethod method) {
        return (method.isAnnotatedWith(PostMapping.class)
                || method.isAnnotatedWith(PutMapping.class)
                || method.isAnnotatedWith(PatchMapping.class)
                || method.isAnnotatedWith(DeleteMapping.class));
    }

    /**
     * Admin-gated = a workspace admin/owner gate or the instance-admin authority, on the method OR its
     * controller. Class-level gates count, since some controllers declare the gate once on the class.
     */
    static boolean isAdminGated(JavaMethod method) {
        return (hasWorkspaceAdminGate(method)
                || hasWorkspaceAdminGate(method.getOwner())
                || isInstanceAdminGated(method)
                || isInstanceAdminGated(method.getOwner()));
    }

    private static boolean hasWorkspaceAdminGate(HasAnnotations<?> element) {
        return element.getAnnotations().stream()
                .map(a -> a.getRawType().getSimpleName())
                .anyMatch(n -> n.equals("RequireAtLeastWorkspaceAdmin") || n.equals("RequireWorkspaceOwner"));
    }

    private static boolean isInstanceAdminGated(HasAnnotations<?> element) {
        // Meta-annotated too, matching isController: a composed @InstanceAdmin annotation would
        // otherwise take its endpoints out of the rule entirely.
        return element.tryGetAnnotationOfType(PreAuthorize.class)
                .or(() -> element.getAnnotations().stream()
                        .flatMap(a -> a.getRawType().tryGetAnnotationOfType(PreAuthorize.class).stream())
                        .findFirst())
                .map(a -> a.value().contains(INSTANCE_ADMIN_AUTHORITY))
                .orElse(false);
    }
}
