package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class TwoPeopleOneWorkspaceRouteTest extends AbstractWorkspaceIntegrationTest {
    private static final String CONVERSATION = "/workspaces/{workspaceSlug}/conversation/";
    private static final String CONVERSATION_API = ApiPath.of(CONVERSATION);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private WebTestClient client;

    @Autowired
    private OwnerScope owners;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationTimeline timeline;

    @Autowired
    private SqlReadMeasurement reads;

    @Test
    void shouldReturnTheSameProblemForAForeignOrMissingIdOnEachConversationRoute() {
        var colleague = persistUser("conversation-owner");
        var developer = persistUser("conversation-member");
        String slug = "conversation-ids";
        var workspace = createWorkspace(slug, slug, slug, AccountType.ORG, colleague);
        ensureOwnerMembership(workspace);
        ensureMembership(workspace, developer, WorkspaceRole.MEMBER);
        long owner = accountId(colleague);
        String ownerBearer = "mock-jwt-member-" + owner;
        String bearer = "mock-jwt-member-" + accountId(developer);
        var foreign = Objects.requireNonNull(transactions.execute(tx -> {
            owners.ofJob(owner);
            var conversation = conversations.findOrCreateMain(workspace.getId(), owner);
            var asked = turns.submit(
                    workspace.getId(),
                    conversation.getId(),
                    new ConversationTurnService.Question(
                            ConversationTurn.Surface.APP,
                            "Private question",
                            UUID.randomUUID().toString(),
                            null,
                            null));
            var answer = entries.append(
                    workspace.getId(), conversation.getId(), ConversationLedgerFixtures.hephaestus("Private answer"));
            return new ForeignIds(asked.turn().getId(), asked.question().getId(), answer.getId());
        }));

        var ownershipChecks = reads.measure(() -> {
            owners.ofJob(owner);
            timeline.requireOwnedEntry(workspace.getId(), owner, foreign.question());
            timeline.requireOwnedTurn(workspace.getId(), owner, foreign.turn());
            return foreign;
        });
        assertThat(ownershipChecks.cost().entities())
                .as("the writer must first load the entry and turn under their locks")
                .isZero();

        var routes = mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()
                        .filter(path -> path.startsWith(CONVERSATION_API)
                                && path.substring(CONVERSATION_API.length()).contains("{"))
                        .map(path -> path.substring(ApiPath.PREFIX.length()))
                        .flatMap(path -> mapping.getMethodsCondition().getMethods().stream()
                                .map(method -> new Route(path, method))))
                .toList();
        assertThat(routes).isNotEmpty();
        for (Route route : routes) {
            UUID id = foreign.forRoute(route);
            assertThat(problem(route, slug, bearer, id))
                    .as("%s %s", route.method(), route.path())
                    .usingRecursiveComparison()
                    .ignoringFields("instance", "properties.requestId")
                    .isEqualTo(problem(route, slug, bearer, UUID.randomUUID()));
            request(route, slug, ownerBearer, id).exchange().expectStatus().is2xxSuccessful();
        }
    }

    private ProblemDetail problem(Route route, String slug, String bearer, UUID id) {
        var problem = Objects.requireNonNull(request(route, slug, bearer, id)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody());
        problem.setDetail(Objects.requireNonNull(problem.getDetail()).replace(id.toString(), "{id}"));
        return problem;
    }

    private WebTestClient.RequestHeadersSpec<?> request(Route route, String slug, String bearer, UUID id) {
        var request = client.method(HttpMethod.valueOf(route.method().name()))
                .uri(route.path(), slug, id)
                .headers(headers -> headers.setBearerAuth(bearer));
        return switch (route.method()) {
            case PATCH -> request.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("status", "STOPPED"));
            case PUT -> request.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("vote", "HELPFUL"));
            default -> request;
        };
    }

    private record Route(String path, RequestMethod method) {}

    private record ForeignIds(UUID turn, UUID question, UUID answer) {
        UUID forRoute(Route route) {
            if (route.path().startsWith(CONVERSATION + "turns/")) return turn;
            if (route.path().startsWith(CONVERSATION + "entries/")) {
                return route.path().endsWith("}") && route.method() == RequestMethod.DELETE ? question : answer;
            }
            throw new IllegalStateException("Add an ID fixture for " + route.path());
        }
    }
}
