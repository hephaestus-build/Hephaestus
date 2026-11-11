package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Every instance-admin mutation, and every workspace role mutation, must declare whether it needs a recent
 * sign-in: {@link RequiresRecentSignIn} asks the caller to confirm access first, {@link RecentSignInExempt} says
 * why it deliberately does not. An endpoint that declares neither is how the gate quietly stops covering the
 * surface it was built for — the same failure {@code AuditByDefaultArchTest} prevents for the audit trail.
 *
 * <p>Other workspace-admin mutations are a tenant's own configuration and stay outside this gate. A workspace
 * role mutation is in scope because it can grant or take away a workspace's top tier: a change that grants,
 * demotes or removes an owner asks for a recent sign-in.
 */
class RecentSignInByDefaultArchTest extends HephaestusArchitectureTest {

    private static final String INSTANCE_ADMIN_AUTHORITY = "app_admin";
    private static final String WORKSPACE_ROLE = "WORKSPACE_ROLE";

    @Test
    void everyInstanceAdminMutationDeclaresItsRecentSignInStatus() {
        List<String> undeclared = instanceAdminMutations()
                .filter(m -> !m.isAnnotatedWith(RequiresRecentSignIn.class)
                        && !m.isAnnotatedWith(RecentSignInExempt.class)
                        && !m.getOwner().isAnnotatedWith(RecentSignInExempt.class))
                .map(RecentSignInByDefaultArchTest::name)
                .sorted()
                .toList();

        assertThat(undeclared).as("""
                These instance-admin mutation endpoints declare neither @RequiresRecentSignIn nor \
                @RecentSignInExempt. Decide: if the action changes who can reach what, or stores a \
                credential, mark it @RequiresRecentSignIn; otherwise mark it — or its controller — \
                @RecentSignInExempt(reason="…"). An undeclared admin action is how a re-authentication \
                gate silently stops covering the surface it was built for.""").isEmpty();
    }

    @Test
    void everyWorkspaceRoleMutationDeclaresItsRecentSignInStatus() {
        List<String> undeclared = workspaceRoleMutations()
                .filter(m -> !m.isAnnotatedWith(RequiresRecentSignIn.class)
                        && !m.isAnnotatedWith(RecentSignInExempt.class)
                        && !m.getOwner().isAnnotatedWith(RecentSignInExempt.class))
                .map(RecentSignInByDefaultArchTest::name)
                .sorted()
                .toList();

        assertThat(undeclared).as("""
                These workspace role mutation endpoints declare neither @RequiresRecentSignIn nor \
                @RecentSignInExempt. One that can grant, demote or remove an owner declares \
                @RequiresRecentSignIn(OWNER_CHANGE) and goes through MembershipService.authorizeRoleChange; \
                one that cannot declares @RecentSignInExempt(reason="…").""").isEmpty();
    }

    /**
     * {@link RequiresRecentSignIn.When#OWNER_CHANGE} leaves the decision to the workspace membership service, so
     * on any other endpoint it would ask for nothing.
     */
    @Test
    void onlyAWorkspaceRoleMutationLeavesTheDecisionToTheOwnerChange() {
        List<String> misplaced = classes.stream()
                .filter(AuditByDefaultArchTest::isController)
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.tryGetAnnotationOfType(RequiresRecentSignIn.class)
                        .filter(a -> a.value() == RequiresRecentSignIn.When.OWNER_CHANGE)
                        .isPresent())
                .filter(m -> !isWorkspaceRoleMutation(m))
                .map(RecentSignInByDefaultArchTest::name)
                .sorted()
                .toList();

        assertThat(misplaced)
                .as("@RequiresRecentSignIn(OWNER_CHANGE) outside a workspace role mutation asks for nothing")
                .isEmpty();
    }

    /**
     * The refusal is recorded on the type the handler already declares for the auth trail, so an attempt
     * and its completion cannot be filed under different names. Without that declaration the gate can
     * only fail at request time, on the very endpoint it is protecting.
     */
    @Test
    void everyGatedMutationDeclaresAnAuthEventTypeToRecordTheRefusalOn() {
        List<String> unrecordable = instanceAdminMutations()
                .filter(m -> m.isAnnotatedWith(RequiresRecentSignIn.class))
                .flatMap(m -> malformedReason(m).map(reason -> name(m) + ": " + reason).stream())
                .sorted()
                .toList();

        assertThat(unrecordable).as("""
                These @RequiresRecentSignIn endpoints cannot record a refusal. Each needs \
                @Audited(ledger = AuditLedger.AUTH_EVENT, type = "…") naming an AuthEvent.EventType \
                constant on the same method.""").isEmpty();
    }

    private static Optional<String> malformedReason(JavaMethod method) {
        Optional<Audited> audited = method.tryGetAnnotationOfType(Audited.class);
        if (audited.isEmpty()) {
            return Optional.of("no @Audited");
        }
        if (audited.get().ledger() != AuditLedger.AUTH_EVENT) {
            return Optional.of("@Audited names " + audited.get().ledger() + ", not AUTH_EVENT");
        }
        String type = audited.get().type();
        boolean known = AuditLedger.AUTH_EVENT.vocabulary().contains(type);
        return known
                ? Optional.empty()
                : Optional.of("'" + type + "' is not an " + AuthEvent.EventType.class.getSimpleName());
    }

    private Stream<JavaMethod> instanceAdminMutations() {
        return classes.stream()
                .filter(AuditByDefaultArchTest::isController)
                .flatMap(c -> c.getMethods().stream())
                .filter(AuditByDefaultArchTest::isMutation)
                .filter(m -> isInstanceAdminGated(m) || isInstanceAdminGated(m.getOwner()));
    }

    /** A mutation filed under the workspace role trail: it adds, changes or removes a member's role. */
    private Stream<JavaMethod> workspaceRoleMutations() {
        return classes.stream()
                .filter(AuditByDefaultArchTest::isController)
                .flatMap(c -> c.getMethods().stream())
                .filter(RecentSignInByDefaultArchTest::isWorkspaceRoleMutation);
    }

    private static boolean isWorkspaceRoleMutation(JavaMethod method) {
        return AuditByDefaultArchTest.isMutation(method)
                && method.tryGetAnnotationOfType(Audited.class)
                        .filter(a -> a.ledger() == AuditLedger.CONFIG_AUDIT
                                && a.type().equals(WORKSPACE_ROLE))
                        .isPresent();
    }

    private static String name(JavaMethod method) {
        return method.getOwner().getSimpleName() + "." + method.getName();
    }

    private static boolean isInstanceAdminGated(HasAnnotations<?> element) {
        return element.tryGetAnnotationOfType(PreAuthorize.class)
                .or(() -> element.getAnnotations().stream()
                        .flatMap(a -> a.getRawType().tryGetAnnotationOfType(PreAuthorize.class).stream())
                        .findFirst())
                .map(a -> a.value().contains(INSTANCE_ADMIN_AUTHORITY))
                .orElse(false);
    }
}
