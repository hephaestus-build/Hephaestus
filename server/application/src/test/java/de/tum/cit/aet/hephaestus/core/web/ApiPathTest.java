package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiPathTest extends BaseUnitTest {

    /** Filters decide on the path that handler mapping and Spring Security match, so an encoded letter changes nothing. */
    @ParameterizedTest
    @CsvSource(
            nullValues = "-",
            value = {
                "/api/user, /user",
                "/%61pi/user, /user",
                "/api/workspaces/acme/teams, /workspaces/acme/teams",
                "/api, -",
                "/apiary, -",
                "/webhooks/github, -",
                "/, -",
            })
    void shouldNameTheDecodedPathRelativeToTheApi(String requestUri, String expected) {
        assertThat(ApiPath.relative(new MockHttpServletRequest("GET", requestUri)))
                .isEqualTo(expected);
    }
}
