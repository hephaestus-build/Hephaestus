package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceEchoControllers.PersonalEcho;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceEchoControllers.WorkspaceContextSnapshot;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A personal space admits only its owner (KR-06): an instance admin gets the answer for a workspace that does not exist
 * on every annotation-mapped API route whose path names {@code {workspaceSlug}}, and no elevation is recorded. The MCP
 * router function and routes that take a workspace another way are covered by the lookups they use
 * ({@code PersonalSpaceIntegrationTest}).
 */
class PersonalSpaceElevationRefusalTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private AuthEventRepository authEvents;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private long ownerId;
    private long adminId;
    private String slug;

    @BeforeEach
    void provision() {
        ownerId = Objects.requireNonNull(persistAccount("Personal owner").getId());
        adminId = Objects.requireNonNull(persistInstanceAdmin("Instance admin").getId());
        long spaceId = PersonalSpaceFixtures.insert(jdbc, ownerId);
        slug = Objects.requireNonNull(
                jdbc.queryForObject("SELECT slug FROM workspace WHERE id = ?", String.class, spaceId));
    }

    @Test
    void shouldAnswerNotFoundToAnInstanceAdminOnEveryMappedSlugRouteOfAnotherPersonsPersonalSpace() {
        String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
        var answered = new ArrayList<String>();
        for (var route : workspaceRoutes()) {
            var request = webTestClient
                    .method(route.method())
                    .uri(route.path().replace("{workspaceSlug}", slug))
                    .headers(headers -> headers.setBearerAuth("mock-jwt-admin-" + adminId))
                    .headers(TestAuthUtils.withCsrf(csrf));
            // A body, so that a route outside /workspaces reaches its workspace lookup rather than refusing the input.
            var exchange = HttpMethod.GET.equals(route.method()) || HttpMethod.DELETE.equals(route.method())
                    ? request.exchange()
                    : request.contentType(MediaType.APPLICATION_JSON)
                            .bodyValue("{}")
                            .exchange();
            int status =
                    exchange.expectBody(Void.class).returnResult().getStatus().value();
            if (status != 404) {
                answered.add(route.method() + " " + route.path() + " -> " + status);
            }
        }

        assertThat(answered)
                .as("routes of a personal space that an instance admin reached")
                .isEmpty();
        assertThat(authEvents.findByAccountSince(adminId, Instant.EPOCH))
                .noneMatch(event -> event.getEventType() == AuthEvent.EventType.WORKSPACE_ELEVATION);
    }

    /** The same admin is still elevated in a team workspace, so the refusal above is the personal space's. */
    @Test
    void shouldStillElevateTheInstanceAdminInATeamWorkspace() {
        Workspace team = workspaces.save(WorkspaceTestFixtures.activeWorkspace("team-next-door"));

        var snapshot = webTestClient
                .get()
                .uri("/workspaces/{slug}/context-echo", team.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth("mock-jwt-admin-" + adminId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceContextSnapshot.class)
                .returnResult()
                .getResponseBody();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.roles()).containsExactly("ADMIN");
    }

    @Test
    void shouldLetTheOwnerIntoARouteThatAllowsAPersonalSpaceAndNoOther() {
        var echo = webTestClient
                .get()
                .uri("/workspaces/{slug}/personal-test/echo", slug)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + ownerId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PersonalEcho.class)
                .returnResult()
                .getResponseBody();
        assertThat(echo).isEqualTo(new PersonalEcho("PERSONAL", List.of("MEMBER")));

        webTestClient
                .get()
                .uri("/workspaces/{slug}/scoped-test/echo", slug)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + ownerId))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldAnswerNotFoundToAnotherSignedInPerson() {
        long otherId = Objects.requireNonNull(persistAccount("Someone else").getId());

        webTestClient
                .get()
                .uri("/workspaces/{slug}/personal-test/echo", slug)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + otherId))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    private record Route(HttpMethod method, String path) {}

    /**
     * Every annotation-mapped handler route that names a workspace slug, under {@code /workspaces} or elsewhere, such as
     * an instance admin route, with each other path variable filled in. Router functions are not in this mapping.
     */
    private Set<Route> workspaceRoutes() {
        var routes = new LinkedHashSet<Route>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                return;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns.getPatternValues()) {
                if (!pattern.startsWith(ApiPath.PREFIX) || !pattern.contains("{workspaceSlug}")) {
                    continue;
                }
                String path = pattern.substring(ApiPath.PREFIX.length())
                        .replaceAll("\\{(?!workspaceSlug})[^}]*}", "1")
                        .replace("**", "x");
                if (methods.isEmpty()) {
                    routes.add(new Route(HttpMethod.GET, path));
                }
                methods.stream()
                        .filter(method -> method != RequestMethod.OPTIONS)
                        .forEach(method -> routes.add(new Route(HttpMethod.valueOf(method.name()), path)));
            }
        });
        assertThat(routes).as("workspace routes").hasSizeGreaterThan(50);
        return routes;
    }
}
