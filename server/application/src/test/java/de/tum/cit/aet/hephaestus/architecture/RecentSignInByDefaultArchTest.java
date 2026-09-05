package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Every instance-admin mutation must declare whether it needs a recent sign-in: {@link RequiresRecentSignIn}
 * asks the caller to confirm access first, {@link RecentSignInExempt} says why it deliberately does not.
 * An endpoint that declares neither is how the gate quietly stops covering the surface it was built for —
 * the same failure {@code AuditByDefaultArchTest} prevents for the audit trail.
 *
 * <p>Only the instance-admin authority is in scope. A workspace-admin mutation is a tenant's own
 * configuration, not instance-wide access, and is deliberately outside this gate.
 */
class RecentSignInByDefaultArchTest extends HephaestusArchitectureTest {

    private static final String INSTANCE_ADMIN_AUTHORITY = "app_admin";

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

    private java.util.stream.Stream<JavaMethod> instanceAdminMutations() {
        return classes.stream()
                .filter(AuditByDefaultArchTest::isController)
                .flatMap(c -> c.getMethods().stream())
                .filter(AuditByDefaultArchTest::isMutation)
                .filter(m -> isInstanceAdminGated(m) || isInstanceAdminGated(m.getOwner()));
    }

    private static String name(JavaMethod method) {
        return method.getOwner().getSimpleName() + "." + method.getName();
    }

    private static boolean isInstanceAdminGated(com.tngtech.archunit.core.domain.properties.HasAnnotations<?> element) {
        return element.tryGetAnnotationOfType(PreAuthorize.class)
                .or(() -> element.getAnnotations().stream()
                        .flatMap(a -> a.getRawType().tryGetAnnotationOfType(PreAuthorize.class).stream())
                        .findFirst())
                .map(a -> a.value().contains(INSTANCE_ADMIN_AUTHORITY))
                .orElse(false);
    }
}
