package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixtures for {@link RecentSignInByDefaultArchTest#unrecordableReason}: a {@code refusal} on a handler without an
 * {@link Audited} would record the refusals and silently drop every successful call.
 */
@Tag("architecture")
class RecentSignInRefusalDetectionTest {

    @RestController
    static class RefusalController {

        @DeleteMapping("/audited")
        @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_STATUS")
        @RequiresRecentSignIn(refusal = "WORKSPACE_DELETION")
        public void audited() {}

        @DeleteMapping("/unaudited")
        @RequiresRecentSignIn(refusal = "WORKSPACE_DELETION")
        public void unaudited() {}
    }

    private static final JavaClasses FIXTURES = new ClassFileImporter().importClasses(RefusalController.class);

    @Test
    void refusalBesideAnAuditOnAnotherLedgerIsRecordable() {
        assertThat(RecentSignInByDefaultArchTest.unrecordableReason(method("audited")))
                .isEmpty();
    }

    @Test
    void refusalWithoutAnAuditFails() {
        assertThat(RecentSignInByDefaultArchTest.unrecordableReason(method("unaudited")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("without @Audited"));
    }

    private static JavaMethod method(String name) {
        return FIXTURES.get(RefusalController.class).getMethod(name);
    }
}
