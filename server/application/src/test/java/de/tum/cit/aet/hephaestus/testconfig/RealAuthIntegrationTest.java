package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtSigningKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "hephaestus.workspace.subdomains.enabled=true",
                    "hephaestus.workspace.subdomains.base-domain=hephaestus.build",
            "hephaestus.webapp.url=https://hephaestus.build", "hephaestus.auth.issuer=https://hephaestus.build",
            "hephaestus.auth.api-base-path=/api"
        })
// A hang guard, not a latency budget: the 5s default failed whole suites whenever one request
// crossed it under CI load, reported as "Timeout on blocking read" with no failing assertion.
@AutoConfigureWebTestClient(timeout = "30s")
@Tag("integration")
public abstract class RealAuthIntegrationTest {

    @Autowired
    private DatabaseTestUtils databaseTestUtils;

    @Autowired
    private JwtSigningKeyService signingKeyService;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        RealAuthDatasource.register(registry);
    }

    @BeforeEach
    void cleanDatabase() {
        databaseTestUtils.cleanDatabase();
        signingKeyService.ensureActiveKey();
    }
}
