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

    // Closing the scopes is the operation; their bindings are intentionally unread.
    @SuppressWarnings("try")
    @Test
    void shouldNameTheTypesWhoseMarkersAloneOpenTheBypass() {
        assertThat(TenancyBypass.silencingTypes()).isEmpty();
        try (TenancyBypass.Scope service = TenancyBypass.openForType("service", String.class)) {
            try (TenancyBypass.Scope repository = TenancyBypass.openForType("repository", Integer.class)) {
                assertThat(TenancyBypass.silencingTypes()).containsExactly("String", "Integer");
                try (TenancyBypass.Scope method = TenancyBypass.open("method")) {
                    assertThat(TenancyBypass.silencingTypes())
                            .as("a method-level marker states its own reason, so nothing is silenced by type")
                            .isEmpty();
                }
                assertThat(TenancyBypass.silencingTypes()).containsExactly("String", "Integer");
            }
        }
        assertThat(TenancyBypass.isActive()).isFalse();
        assertThat(TenancyBypass.silencingTypes()).isEmpty();
    }
}
