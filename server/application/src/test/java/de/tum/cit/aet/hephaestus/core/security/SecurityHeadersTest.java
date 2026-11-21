package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class SecurityHeadersTest extends BaseUnitTest {

    @Test
    void shouldReplaceOnlyTheSelfScriptSourceWithTheNonceOnThePage() {
        String policy = SecurityHeaders.contentSecurityPolicy(true, null);

        String page = SecurityHeaders.pagePolicy(policy, "bm9uY2U=");

        assertThat(page)
                .contains("script-src 'nonce-bm9uY2U=' 'strict-dynamic'")
                .doesNotContain("script-src 'self'")
                .isEqualTo(policy.replace("script-src 'self'", "script-src 'nonce-bm9uY2U=' 'strict-dynamic'"));
    }

    @Test
    void shouldRefuseAPolicyWithoutASelfScriptSourceToReplace() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SecurityHeaders.pagePolicy("default-src 'self'; script-src 'none'", "bm9uY2U="))
                .withMessageContaining("script-src 'self'");
    }
}
