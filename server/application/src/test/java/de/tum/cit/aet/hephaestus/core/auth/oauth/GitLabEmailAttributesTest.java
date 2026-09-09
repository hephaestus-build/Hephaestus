package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

@Tag("unit")
class GitLabEmailAttributesTest {
    @Test
    void shouldUseConfirmedPrimaryEmailWithoutChangingTheOriginalProviderResponse() {
        Map<String, Object> original =
                Map.of("id", 123L, "email", "developer@example.test", "confirmed_at", "2026-01-01T00:00:00Z");
        var attributes = GitLabEmailAttributes.withVerification(original);
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "id");
        var resolved = new VerifiedEmailResolver().resolve("institution-gitlab", principal);
        assertThat(resolved.email()).isEqualTo("developer@example.test");
        assertThat(resolved.verified()).isTrue();
        assertThat(original).doesNotContainKey("email_verified");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "true", "2026-01-01"})
    void shouldRejectMalformedConfirmationInsteadOfTrustingAnUnrelatedBoolean(String confirmation) {
        var attributes = GitLabEmailAttributes.withVerification(
                Map.of("email", "developer@example.test", "confirmed_at", confirmation, "email_verified", true));
        assertThat(attributes.get("email_verified")).isEqualTo(false);
    }

    @Test
    void shouldRejectMissingNullOrNonStringConfirmation() {
        var attributes = new HashMap<String, Object>();
        attributes.put("email", "developer@example.test");
        assertThat(GitLabEmailAttributes.withVerification(attributes).get("email_verified"))
                .isEqualTo(false);
        attributes.put("confirmed_at", null);
        assertThat(GitLabEmailAttributes.withVerification(attributes).get("email_verified"))
                .isEqualTo(false);
        attributes.put("confirmed_at", true);
        assertThat(GitLabEmailAttributes.withVerification(attributes).get("email_verified"))
                .isEqualTo(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "temp-email-for-oauth-provider-123@example.test"})
    void shouldRejectMissingOrSyntheticAddressesEvenOnAConfirmedAccount(String email) {
        assertThat(GitLabEmailAttributes.withVerification(
                                Map.of("email", email, "confirmed_at", "2026-01-01T00:00:00Z"))
                        .get("email_verified"))
                .isEqualTo(false);
    }
}
