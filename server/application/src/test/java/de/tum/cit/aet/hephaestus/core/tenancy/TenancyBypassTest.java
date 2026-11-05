package de.tum.cit.aet.hephaestus.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class TenancyBypassTest extends BaseUnitTest {

    @Test
    void inactiveByDefault() {
        assertThat(TenancyBypass.isActive()).isFalse();
    }

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    @Test
    void scopeActivatesAndClosesCleanly() {

        try (TenancyBypass.Scope ignored = TenancyBypass.open("admin op")) {
            assertThat(TenancyBypass.isActive()).isTrue();
        }
        assertThat(TenancyBypass.isActive()).isFalse();
    }

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    @Test
    void nestedScopesUseDepthCounter() {
        try (TenancyBypass.Scope outer = TenancyBypass.open("outer")) {
            assertThat(TenancyBypass.isActive()).isTrue();
            try (TenancyBypass.Scope inner = TenancyBypass.open("inner")) {
                assertThat(TenancyBypass.isActive()).isTrue();
            }
            // Inner closed; outer still open
            assertThat(TenancyBypass.isActive()).isTrue();
        }
        assertThat(TenancyBypass.isActive()).isFalse();
    }

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    @Test
    void scopeStillDecrementsWhenBodyThrows() {
        try {
            try (TenancyBypass.Scope ignored = TenancyBypass.open("body throws")) {
                throw new IllegalStateException("boom");
            }
        } catch (IllegalStateException expected) {
            // swallow
        }
        assertThat(TenancyBypass.isActive())
                .as("try-with-resources must decrement depth even when the body throws")
                .isFalse();
    }
}
