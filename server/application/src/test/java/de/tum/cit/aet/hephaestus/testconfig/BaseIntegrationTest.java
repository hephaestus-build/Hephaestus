package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.agent.handler.AgentHandlerTestDoubles;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnClaims;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewLookupData;
import de.tum.cit.aet.hephaestus.practices.scan.ScanReportTestData;
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
// Allow bounded waits for database transactions in request tests.
@AutoConfigureWebTestClient(timeout = "30s")
@Import({
    TestSecurityConfig.class,
    TestAsyncConfiguration.class,
    RecordingScmEventListener.class,
    StubInstallationRepositoryEnumerator.class,
    SharedTestDoubles.class,
    AgentHandlerTestDoubles.class,
    WorkspaceEchoControllers.ScopedEchoController.class,
    WorkspaceEchoControllers.WorkspaceContextEchoController.class,
    ScriptedGitHubTransport.class,
    // Stateless review fixtures: one shared import keeps every test that uses them in this context.
    // ScanReportTestData needs the server role, which every subclass keeps.
    ReviewIntegrationData.class,
    ReviewLookupData.class,
    ReviewAdmissionData.class,
    ScanReportTestData.class,
    MentorTurnClaims.class,
})
@Tag("integration")
@TestPropertySource(
        properties = {
            "hephaestus.features.flags.page-experience-metrics=true",
            "hephaestus.features.flags.browser-fault-reports=true",
            "hephaestus.llm.display-currency="
        })
public abstract class BaseIntegrationTest {

    @Autowired
    protected DatabaseTestUtils databaseTestUtils;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // The roles as the chart runs them: the owner migrates, the application role reads and writes rows.
        var application = PostgreSQLTestContainer.application();
        var owner = PostgreSQLTestContainer.owner();
        registry.add("spring.datasource.url", application::jdbcUrl);
        registry.add("spring.datasource.username", application::username);
        registry.add("spring.datasource.password", application::password);
        registry.add("spring.liquibase.user", owner::username);
        registry.add("spring.liquibase.password", owner::password);

        // Nested REQUIRES_NEW transactions need a second connection while the outer transaction remains open.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        // Each cached test context has its own pool, so an idle one holds no connection.
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("spring.datasource.hikari.max-lifetime", () -> "300000"); // 5 minutes
        registry.add("spring.datasource.hikari.connection-timeout", () -> "10000"); // 10 seconds
        registry.add("spring.datasource.hikari.idle-timeout", () -> "10000"); // HikariCP's lowest
        registry.add("spring.datasource.hikari.validation-timeout", () -> "5000"); // 5 seconds
        registry.add("spring.datasource.hikari.leak-detection-threshold", () -> "10000"); // 10 seconds
        registry.add("spring.datasource.hikari.connection-test-query", () -> "SELECT 1");
    }
}
