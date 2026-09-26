package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ScmOriginTest extends BaseUnitTest {

    @ParameterizedTest
    @CsvSource({
        "https://gitlab.lrz.de, https://gitlab.lrz.de",
        "HTTPS://GitLab.LRZ.de/, https://gitlab.lrz.de",
        "https://gitlab.lrz.de:443, https://gitlab.lrz.de",
        "http://127.0.0.1:80, http://127.0.0.1",
        "https://gitlab.example.com:8443, https://gitlab.example.com:8443",
        "http://127.0.0.1:8929, http://127.0.0.1:8929",
    })
    void shouldAgreeOnSemanticallyIdenticalUrls(String url, String origin) {
        assertThat(ScmOrigin.of(url)).contains(origin);
    }

    @ParameterizedTest
    @CsvSource({
        "https://[2606:4700:4700:0:0:0:0:1111], https://[2606:4700:4700::1111]:443",
        "https://[2606:4700:4700::1111]:8443, HTTPS://[2606:4700:4700:0000:0000:0000:0000:1111]:8443",
    })
    void shouldCompareIpv6LiteralsByAddress(String url, String sameInstance) {
        assertThat(ScmOrigin.of(url)).isPresent().isEqualTo(ScmOrigin.of(sameInstance));
    }

    @ParameterizedTest
    @CsvSource({
        "https://[2606:4700:4700::1111], https://[2606:4700:4700::1112]",
        "https://[2606:4700:4700::1111]:8443, https://[2606:4700:4700::1111]"
    })
    void shouldKeepDistinctIpv6InstancesApart(String url, String otherInstance) {
        assertThat(ScmOrigin.of(url)).isPresent().isNotEqualTo(ScmOrigin.of(otherInstance));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "  ",
                "gitlab.lrz.de",
                "https://",
                "https://gitlab lrz.de",
                "https://[::ffff:1.1.1.1]",
                "https://[fe80::1%25eth0]"
            })
    void shouldHaveNoOriginWithoutAComparableSchemeAndHost(@Nullable String url) {
        assertThat(ScmOrigin.of(url)).isEmpty();
    }
}
