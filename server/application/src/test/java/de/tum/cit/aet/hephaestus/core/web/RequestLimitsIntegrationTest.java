package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The request limits of the shipped {@code application.yml}, on a real Tomcat listener. Tomcat applies them before
 * any filter or controller runs, so they bound what a pre-auth request can make the server parse.
 */
@Tag("integration")
class RequestLimitsIntegrationTest {

    private static final int HEADER_LIMIT = 8 * 1024;
    private static final int FORM_LIMIT = 2 * 1024 * 1024;

    @Test
    void shouldRefuseAHeaderBlockOverTheLimitWhenAClientSendsOne() throws IOException {
        try (var context = start()) {
            int port = port(context);
            assertThat(exchange(port, get(HEADER_LIMIT - 1024))).startsWith("HTTP/1.1 200").endsWith("ok");
            assertThat(exchange(port, get(HEADER_LIMIT + 1024))).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    void shouldLeaveAFormBodyOverTheLimitUnparsedWhenAClientPostsOne() throws IOException {
        try (var context = start()) {
            int port = port(context);
            String body = "payload=" + "a".repeat(FORM_LIMIT - 1024);
            assertThat(exchange(port, form(body.length()) + body))
                    .startsWith("HTTP/1.1 200")
                    .endsWith(Integer.toString(FORM_LIMIT - 1024));
            // Tomcat decides from Content-Length, before it reads the body, and leaves the required parameter
            // missing. The body is never sent: it is larger than the swallow limit, so Tomcat closes the connection.
            assertThat(exchange(port, form(FORM_LIMIT + 1024))).startsWith("HTTP/1.1 400");
        }
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplication(LimitsApplication.class)
                .run("--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off");
    }

    private static int port(ConfigurableApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    private static String get(int padding) {
        return "GET /limits HTTP/1.1\r\nHost: localhost\r\nX-Padding: " + "a".repeat(padding)
                + "\r\nConnection: close\r\n\r\n";
    }

    /** The request line and headers of a form post whose body is {@code length} bytes. */
    private static String form(int length) {
        return "POST /limits HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                + "Content-Length: " + length + "\r\nConnection: close\r\n\r\n";
    }

    /** The whole response: the status line first, the body last. */
    private static String exchange(int port, String request) throws IOException {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LimitsController.class)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
    })
    static class LimitsApplication {}

    @RestController
    static class LimitsController {

        @GetMapping("/limits")
        String probe() {
            return "ok";
        }

        @PostMapping("/limits")
        String form(@RequestParam("payload") String payload) {
            return Integer.toString(payload.length());
        }
    }
}
