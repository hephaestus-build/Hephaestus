package de.tum.cit.aet.hephaestus.agent.composer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.proxy.ReviewModelGateway;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The gateway of one replayed composition, over loopback HTTP: the composition routes of the run, served by
 * {@link CompositionSessions} as the gateway serves them, and the model proxy, which answers with the case's model
 * answers. A composer loop in another process composes against it exactly as against the real gateway.
 */
final class ComposerReplayGateway implements AutoCloseable {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final HttpServer server;
    private final JsonMapper mapper;
    private final CompositionSessions sessions;
    private final ReviewJob job;
    private final ResultAdmissionService.AdmissionIdentity identity;
    private final Instant deadline;
    private final Function<byte[], ReviewModelGateway.Answer> model;

    ComposerReplayGateway(
            JsonMapper mapper,
            CompositionSessions sessions,
            ReviewJob job,
            ResultAdmissionService.AdmissionIdentity identity,
            Instant deadline,
            Function<byte[], ReviewModelGateway.Answer> model)
            throws IOException {
        this.mapper = mapper;
        this.sessions = sessions;
        this.job = job;
        this.identity = identity;
        this.deadline = deadline;
        this.model = model;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/composition/", this::composition);
        server.createContext("/llm/", this::model);
        server.start();
    }

    /** The gateway's base URL: the composition routes are below {@code /composition}, the proxy below {@code /llm}. */
    String url() {
        return "http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort();
    }

    private void composition(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String route = exchange.getRequestURI().getPath().substring("/composition".length());
            try {
                answer(exchange, route, body);
            } catch (ResultAdmissionService.StaleAttemptException | AdmissionConflictException conflict) {
                send(exchange, 409, null);
            } catch (ReviewCompositionContinuation.InputsUnavailableException unavailable) {
                send(exchange, 410, JSON.stringNode(unavailable.erased() ? "INPUTS_ERASED" : "INPUTS_GONE"));
            } catch (RuntimeException failed) {
                send(exchange, 500, null);
            }
        }
    }

    private void answer(HttpExchange exchange, String route, JsonNode body) throws IOException {
        if (route.equals("/next")) {
            var finished = EnumSet.noneOf(FeedbackDestination.class);
            body.path("finished").forEach(name -> finished.add(FeedbackDestination.valueOf(name.asString())));
            var next = sessions.next(job, identity, finished).orElse(null);
            if (next == null) {
                send(exchange, 204, null);
                return;
            }
            var answer = JSON.objectNode();
            switch (next) {
                case CompositionSessions.Session session -> {
                    answer.set("session", mapper.valueToTree(session));
                    answer.putNull("unavailable");
                }
                case CompositionSessions.Unavailable unavailable -> {
                    answer.putNull("session");
                    answer.put("unavailable", unavailable.cause().name());
                }
            }
            answer.put("deadline", deadline.toString());
            answer.put("lastAttempt", sessions.lastAttempt(job));
            send(exchange, 200, answer);
        } else if (route.startsWith("/tools/")) {
            var call = new CompositionSessions.ToolCall(
                    FeedbackDestination.valueOf(body.path("destination").asString()),
                    body.path("callId").asString(),
                    route.substring("/tools/".length()),
                    body.path("arguments"),
                    body.path("publicationContext").asString(null),
                    Instant.parse(body.path("deadline").asString()));
            send(exchange, 200, mapper.valueToTree(sessions.tool(job, identity, call)));
        } else if (route.equals("/stop")) {
            String reason = body.path("reason").asString();
            if (!reason.equals("COMPLETE") && !CompositionStop.loopReported(reason))
                throw new IllegalArgumentException("Not a stop the composer loop reports");
            sessions.stop(job, reason);
            send(exchange, 204, null);
        } else if (route.equals("/close")) {
            var stops = new EnumMap<FeedbackDestination, CompositionStop>(FeedbackDestination.class);
            body.path("stops").properties().forEach(entry -> {
                if (!CompositionStop.loopReported(entry.getValue().asString()))
                    throw new IllegalArgumentException("Not a stop the composer loop reports");
                stops.put(
                        FeedbackDestination.valueOf(entry.getKey()),
                        CompositionStop.valueOf(entry.getValue().asString()));
            });
            var handedOn = sessions.close(job, identity, stops);
            send(
                    exchange,
                    200,
                    JSON.objectNode()
                            .set("handedOn", handedOn == null ? JSON.nullNode() : JSON.stringNode(handedOn.name())));
        } else send(exchange, 404, null);
    }

    private void model(HttpExchange exchange) throws IOException {
        try (exchange) {
            var answer = model.apply(exchange.getRequestBody().readAllBytes());
            if (answer.refusal() != null)
                exchange.getResponseHeaders()
                        .add("Hephaestus-Proxy-Refusal", answer.refusal().name());
            exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
            if (answer.body().length > 0) exchange.getResponseBody().write(answer.body());
        }
    }

    private void send(HttpExchange exchange, int status, @Nullable JsonNode body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
