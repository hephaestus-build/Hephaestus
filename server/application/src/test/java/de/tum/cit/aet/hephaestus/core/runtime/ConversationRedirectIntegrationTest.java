package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Exercises the shipped serving configuration, before any SPA code can run. */
@Tag("integration")
class ConversationRedirectIntegrationTest {
    private static final Path WEBAPP = Path.of(System.getProperty("basedir", "."), "../../webapp");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static GenericContainer<?> nginx;

    @BeforeAll
    static void startServingBoundary() throws Exception {
        var image = Pattern.compile("(?m)^FROM (nginx:[^ ]+) AS os-packages$")
                .matcher(Files.readString(WEBAPP.resolve("Dockerfile")));
        assertThat(image.find()).as("the webapp's pinned Nginx runtime image").isTrue();
        nginx = new GenericContainer<>(image.group(1))
                .withExposedPorts(8080)
                .withCreateContainerCmdModifier(
                        command -> command.withEntrypoint("nginx").withUser("101:101"))
                .withCommand("-g", "daemon off;")
                .withTmpFs(Map.of("/tmp/hephaestus-webapp", "rw,uid=101,gid=101"))
                .waitingFor(Wait.forListeningPort());
        for (var mapping : Map.of(
                        "nginx-main.conf", "/etc/nginx/nginx.conf",
                        "nginx-globals.conf", "/etc/nginx/hephaestus/globals.conf",
                        "nginx-http.conf", "/etc/nginx/hephaestus/http.conf",
                        "nginx.conf", "/etc/nginx/conf.d/default.conf",
                        "security-headers.conf", "/etc/nginx/security-headers.conf")
                .entrySet()) {
            nginx.withCopyFileToContainer(
                    MountableFile.forHostPath(WEBAPP.resolve("docker").resolve(mapping.getKey())), mapping.getValue());
        }
        nginx.start();
    }

    @AfterAll
    static void stopServingBoundary() {
        if (nginx != null) nginx.close();
    }

    @ParameterizedTest
    @CsvSource({
        "/mentor,/conversation",
        "/mentor/,/conversation",
        "/w/acme/mentor,/w/acme/conversation",
        "/w/acme/mentor/,/w/acme/conversation",
        "/w/Old_Team/mentor,/w/Old_Team/conversation"
    })
    void shouldPermanentlyRedirectLegacyDocumentRequestsWithoutChangingTheQuery(String oldPath, String newPath)
            throws Exception {
        for (String query : new String[] {"", "?thread=thread-id&entry=entry-id&extra=a%2Fb%20c"}) {
            var response = request(oldPath + query);
            assertThat(response.statusCode()).isEqualTo(308);
            assertThat(response.headers().firstValue("location")).contains(newPath + query);
        }
    }

    @ParameterizedTest
    @CsvSource({"/api/mentor", "/webhooks/mentor", "/w/acme/mentor/other", "/mentorship"})
    void shouldNotRedirectOtherRoutes(String path) throws Exception {
        var response = request(path);
        assertThat(response.statusCode() / 100).isNotEqualTo(3);
        assertThat(response.headers().firstValue("location")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"/w/a%0d%0aX-Injected:%201/mentor", "/w/x%3Fy/mentor"})
    void shouldNotRedirectWhenADecodedSlugContainsHeaderOrQueryDelimiters(String path) throws Exception {
        var response = request(path);
        assertThat(response.statusCode() / 100).isNotEqualTo(3);
        assertThat(response.headers().firstValue("location")).isEmpty();
        assertThat(response.headers().firstValue("x-injected")).isEmpty();
    }

    private static HttpResponse<Void> request(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://" + nginx.getHost() + ":" + nginx.getMappedPort(8080) + path))
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
