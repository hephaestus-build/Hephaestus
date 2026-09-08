package de.tum.cit.aet.hephaestus.testconfig;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** One shared real-auth context for organizational sign-in and fixture-driven directory HTTP. */
@TestPropertySource(
        properties = {
            "hephaestus.auth.oidc.allowed-issuers=https://identity.example.com/realms/team,https://identity.example.com/realms/other",
            "hephaestus.auth.api-base-path=",
            "hephaestus.webapp.url=https://app.example.com"
        })
@org.springframework.test.context.jdbc.Sql(
        scripts = "/db/auth-event-sequence.sql",
        executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class OrganizationalIdentityIntegrationTest extends RealAuthIntegrationTest {
    @MockitoBean(name = "oidcRequestFactory")
    protected ClientHttpRequestFactory requests;
}
