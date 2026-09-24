package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.agent.handler.AgentHandlerTestDoubles;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// A hang guard, not a latency budget: the 5s default failed whole suites whenever one request
// crossed it under CI load, reported as "Timeout on blocking read" with no failing assertion.
@AutoConfigureWebTestClient(timeout = "30s")
@Import({
    TestSecurityConfig.class,
    TestAsyncConfiguration.class,
    RecordingScmEventListener.class,
    StubInstallationRepositoryEnumerator.class,
    StubMentorChatStarter.class,
    SharedTestDoubles.class,
    AgentHandlerTestDoubles.class,
    WorkspaceEchoControllers.ScopedEchoController.class,
    WorkspaceEchoControllers.WorkspaceContextEchoController.class,
})
@Tag("integration")
@TestPropertySource(
        properties = {"hephaestus.features.flags.gitlab-workspace-creation=true", "hephaestus.llm.display-currency="})
public abstract class BaseIntegrationTest {

    @Autowired
    protected DatabaseTestUtils databaseTestUtils;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        var postgres = PostgreSQLTestContainer.getInstance();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Nested REQUIRES_NEW transactions need a second connection while the outer transaction remains open.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.max-lifetime", () -> "300000"); // 5 minutes
        registry.add("spring.datasource.hikari.connection-timeout", () -> "10000"); // 10 seconds
        registry.add("spring.datasource.hikari.idle-timeout", () -> "60000"); // 1 minute
        registry.add("spring.datasource.hikari.validation-timeout", () -> "5000"); // 5 seconds
        registry.add("spring.datasource.hikari.leak-detection-threshold", () -> "10000"); // 10 seconds
        registry.add("spring.datasource.hikari.connection-test-query", () -> "SELECT 1");
    }
}
