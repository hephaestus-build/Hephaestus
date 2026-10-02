package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstalledClientRegistryTest extends BaseUnitTest {

    private static final String ID = "ijkajblcbajjpjbknfgdiiiljipafiko";

    private static InstalledClientRegistry registry(List<String> ids) {
        return new InstalledClientRegistry(AuthPropertiesFixture.withBrowserExtensionIds(ids));
    }

    @Test
    void shouldDeriveExactlyOneCallbackAndOneOriginWhenAnExtensionIdIsConfigured() {
        InstalledClientRegistry registry = registry(List.of(ID));

        assertThat(registry.find(ID, "https://" + ID + ".chromiumapp.org/callback"))
                .contains(new InstalledClient(
                        InstalledClientKind.BROWSER_EXTENSION,
                        ID,
                        "https://" + ID + ".chromiumapp.org/callback",
                        "chrome-extension://" + ID));
        assertThat(registry.origins()).containsExactly("chrome-extension://" + ID);
        assertThat(registry.isRegistered(ID)).isTrue();
    }

    @Test
    void shouldMatchCallbacksOnlyByExactStringWhenLookingUpAClient() {
        InstalledClientRegistry registry = registry(List.of(ID));

        assertThat(registry.find(ID, "https://" + ID + ".chromiumapp.org/callback/"))
                .isEmpty();
        assertThat(registry.find(ID, "https://" + ID + ".chromiumapp.org/callback?x=1"))
                .isEmpty();
        assertThat(registry.find(ID, "HTTPS://" + ID + ".chromiumapp.org/callback"))
                .isEmpty();
        assertThat(registry.find(ID, "https://" + ID + ".chromiumapp.org/other"))
                .isEmpty();
        assertThat(registry.find(
                        "abcdefghijklmnopabcdefghijklmnop",
                        "https://abcdefghijklmnopabcdefghijklmnop.chromiumapp.org/callback"))
                .isEmpty();
        assertThat(registry.isRegistered("abcdefghijklmnopabcdefghijklmnop")).isFalse();
    }

    @Test
    void shouldRegisterNothingWhenNoIdIsConfigured() {
        InstalledClientRegistry registry = registry(List.of());

        assertThat(registry.origins()).isEmpty();
        assertThat(registry.isRegistered(ID)).isFalse();
    }

    @Test
    void shouldRefuseToStartWhenAConfiguredIdIsNotAChromeExtensionId() {
        for (String bad : List.of("short", ID.toUpperCase(java.util.Locale.ROOT), ID.replace('a', 'z'), ID + "a")) {
            assertThatThrownBy(() -> registry(List.of(bad))).isInstanceOf(IllegalStateException.class);
        }
    }
}
