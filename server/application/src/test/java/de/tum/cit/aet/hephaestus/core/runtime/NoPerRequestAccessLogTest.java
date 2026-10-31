package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * {@code application-prod.yml} states, and {@code docs/admin/dsms/} publishes as a GDPR posture, that
 * NO layer of the deployment writes a per-request IP/URL record. That is a claim about every layer at
 * once, only one of which is Java — so it is asserted here over the shipped configuration of each
 * rather than trusted per file.
 *
 * <p>The URL is the personal datum, not the address: the webapp image does not load {@code real_ip}, so the
 * peer it would log is the proxy's address. But an SPA deep link names a person
 * ({@code /w/<slug>/user/<username>}), so one combined-format line per request is a per-request
 * personal-data record regardless.
 *
 * <p>nginx inherits {@code access_log} downwards, so the guard is structural: {@code off} must sit in
 * the {@code server} block, where every location inherits it. A per-location {@code access_log off}
 * covers only the locations that remember to repeat it, and this file is edited by adding locations.
 */
class NoPerRequestAccessLogTest extends BaseUnitTest {

    /** The webapp image ships every nginx config it runs from this directory. */
    private static final Path WEBAPP_NGINX_DIRECTORY = Path.of("..", "..", "webapp", "docker");

    /** The webapp's server block. */
    private static final Path WEBAPP_SERVER_CONF = WEBAPP_NGINX_DIRECTORY.resolve("nginx.conf");

    /** Files the server includes. None may turn the access log back on for every server. */
    private static final List<Path> SHARED_CONFS = List.of(
            WEBAPP_NGINX_DIRECTORY.resolve("nginx-main.conf"),
            WEBAPP_NGINX_DIRECTORY.resolve("nginx-globals.conf"),
            WEBAPP_NGINX_DIRECTORY.resolve("nginx-http.conf"));

    private static final Path PROD_PROFILE = Path.of("src", "main", "resources", "application-prod.yml");

    @Test
    void tomcatAccessLogStaysDisabledInTheProductionProfile() throws Exception {
        assertThat(propertyIn(PROD_PROFILE, "server.tomcat.accesslog.enabled"))
                .as("application-prod.yml claims no layer writes a per-request record; Tomcat is one of them")
                .isEqualTo("false");
    }

    @Test
    void webappNginxDisablesTheAccessLogForTheWholeServerBlock() throws Exception {
        assertThat(directivesAtServerLevel(read(WEBAPP_SERVER_CONF)))
                .as("webapp/docker/nginx.conf must carry `access_log off;` in the server block: "
                        + "nginx's built-in default turns the access log ON, so anything the server "
                        + "block does not override logs the request URL — and SPA deep links name contributors")
                .contains("access_log off");
    }

    /**
     * A location, a shared include or a new config that sets {@code access_log} to a destination would
     * opt requests back in underneath the server-level {@code off}, which is the failure the
     * server-level directive exists to prevent — and it would not show up in the server-level test above.
     * Every {@code .conf} the image ships is read, so a new file cannot slip past this list.
     */
    @Test
    void noShippedNginxConfigReEnablesTheAccessLog() throws Exception {
        List<Path> shipped;
        try (Stream<Path> files = Files.list(WEBAPP_NGINX_DIRECTORY)) {
            shipped = files.filter(file -> file.toString().endsWith(".conf"))
                    .sorted()
                    .toList();
        }
        assertThat(shipped)
                .as("the server modes and the files they include are all shipped from webapp/docker")
                .contains(WEBAPP_SERVER_CONF)
                .containsAll(SHARED_CONFS);

        List<String> reEnabling = new ArrayList<>();
        for (Path file : shipped) {
            read(file)
                    .lines()
                    .map(NoPerRequestAccessLogTest::directive)
                    .filter(line -> line.startsWith("access_log") && !line.equals("access_log off"))
                    .map(line -> file + ": " + line)
                    .forEach(reEnabling::add);
        }

        assertThat(reEnabling)
                .as("every access_log directive in a shipped nginx config must be `off`")
                .isEmpty();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Directives written directly inside a {@code server { … }} block, at whatever depth the block
     * sits: a {@code location} nested inside it does not count.
     */
    private static List<String> directivesAtServerLevel(String conf) {
        List<String> directives = new ArrayList<>();
        Deque<Boolean> blocks = new ArrayDeque<>();
        for (String raw : conf.lines().toList()) {
            String line = directive(raw);
            if (line.endsWith("{")) {
                blocks.push(line.replace(" ", "").equals("server{"));
            } else if (line.startsWith("}")) {
                blocks.pop();
            } else if (!line.isEmpty() && Boolean.TRUE.equals(blocks.peek())) {
                directives.add(line);
            }
        }
        return directives;
    }

    /** Strips the trailing {@code ;}, comments and indentation so directives compare by content. */
    private static String directive(String line) {
        String stripped = line.strip();
        int comment = stripped.indexOf('#');
        if (comment >= 0) {
            stripped = stripped.substring(0, comment).strip();
        }
        return stripped.endsWith(";")
                ? stripped.substring(0, stripped.length() - 1).strip()
                : stripped;
    }

    private static @Nullable String propertyIn(Path yaml, String key) throws IOException {
        return new YamlPropertySourceLoader()
                .load(yaml.toString(), new FileSystemResource(yaml)).stream()
                        .map(PropertySource.class::cast)
                        .filter(source -> source.containsProperty(key))
                        .findFirst()
                        .map(source -> String.valueOf(source.getProperty(key)))
                        .orElse(null);
    }
}
