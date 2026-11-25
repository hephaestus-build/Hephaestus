package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import de.tum.cit.aet.hephaestus.core.web.ClientInputs;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import java.time.Instant;
import java.util.List;
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

    @Autowired
    private SqlRequestTrace requests;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private WorkspaceModelAssignmentRepository bindings;

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
        var foreign = conversationOf(workspace.getId(), owner);

        var ownershipChecks = reads.measure(() -> {
            owners.ofJob(owner);
            timeline.requireOwnedEntry(workspace.getId(), owner, foreign.question());
            timeline.requireOwnedTurn(workspace.getId(), owner, foreign.turn());
            return foreign;
        });
        assertThat(ownershipChecks.cost().entities())
                .as("the writer must first load the entry and turn under their locks")
                .isZero();

        var routes = routes().stream().filter(Route::namesAnId).toList();
        assertThat(routes).isNotEmpty();
        for (Route route : routes) {
            UUID id = foreign.forRoute(route);
            assertThat(problem(route, slug, bearer, id))
                    .as("%s %s", route.method(), route.path())
                    .usingRecursiveComparison()
                    .ignoringFields("instance", "properties.requestId")
                    .isEqualTo(problem(route, slug, bearer, UUID.randomUUID()));
            request(client, route, slug, ownerBearer, id)
                    .exchange()
                    .expectStatus()
                    .is2xxSuccessful()
                    .expectBody(Void.class);
        }
    }

    /**
     * Each route binds the signed-in account as the owner of its transaction before it reads or writes a conversation
     * row: the owner that the conversation tables' owner policy reads. A read-only route reads in a read-only
     * transaction.
     */
    @Test
    void shouldBindTheSignedInAccountAsOwnerOfEveryConversationStatementWhenARouteReadsOrWritesIt() {
        var developer = persistUser("conversation-bound");
        String slug = "conversation-bound";
        var workspace = createWorkspace(slug, slug, slug, AccountType.ORG, developer);
        ensureOwnerMembership(workspace);
        ensureMembership(workspace, developer, WorkspaceRole.MEMBER);
        // Hephaestus answers a question only in a workspace with a conversation model.
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        bindings.save(LlmCatalogTestFixtures.conversationBinding(
                workspace, models.save(LlmCatalogTestFixtures.model(connection, slug, "bound-model"))));
        long owner = accountId(developer);
        var own = conversationOf(workspace.getId(), owner);

        var routes = routes().stream()
                .filter(route -> !route.path().endsWith("/events"))
                .toList();
        assertThat(routes).extracting(Route::method).contains(RequestMethod.GET, RequestMethod.POST);
        for (Route route : routes) {
            UUID id = route.namesAnId() ? own.forRoute(route) : UUID.randomUUID();
            var measured = requests.measureOwners(
                    client,
                    http -> request(http, route, slug, "mock-jwt-member-" + owner, id)
                            .exchange()
                            .expectStatus()
                            .is2xxSuccessful()
                            .expectBody(Void.class)
                            .returnResult());
            var touched = ConversationStatements.of(measured);
            assertThat(touched)
                    .as("%s %s reads or writes the conversation", route.method(), route.path())
                    .isNotEmpty()
                    .allSatisfy(statement -> assertThat(statement.owner())
                            .as("the owner of %s %s when it ran %s", route.method(), route.path(), statement.sql())
                            .isEqualTo(owner));
            if (route.method() == RequestMethod.GET) {
                assertThat(touched)
                        .allSatisfy(statement -> assertThat(statement.readOnly())
                                .as(
                                        "%s %s reads in a read-only transaction: %s",
                                        route.method(), route.path(), statement.sql())
                                .isTrue());
            }
        }
    }

    /** The account's main conversation with a queued question and an answer. */
    private ConversationIds conversationOf(long workspaceId, long account) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            owners.ofJob(account);
            var conversation = conversations.findOrCreateMain(workspaceId, account);
            var asked = turns.submit(
                    workspaceId,
                    conversation.getId(),
                    new ConversationTurnService.Question(
                            ConversationTurn.Surface.APP,
                            "Private question",
                            UUID.randomUUID().toString(),
                            null,
                            null));
            var answer = entries.append(
                    workspaceId, conversation.getId(), ConversationLedgerFixtures.hephaestus("Private answer"));
            return new ConversationIds(asked.turn().getId(), asked.question().getId(), answer.getId());
        }));
    }

    /** Every conversation route, as its path and method. */
    private List<Route> routes() {
        String conversation = CONVERSATION_API.substring(0, CONVERSATION_API.length() - 1);
        return mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()
                        .filter(path -> path.equals(conversation) || path.startsWith(CONVERSATION_API))
                        .map(path -> path.substring(ApiPath.PREFIX.length()))
                        .flatMap(path -> mapping.getMethodsCondition().getMethods().stream()
                                .map(method -> new Route(path, method))))
                .toList();
    }

    private ProblemDetail problem(Route route, String slug, String bearer, UUID id) {
        var problem = Objects.requireNonNull(request(client, route, slug, bearer, id)
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

    private static WebTestClient.RequestHeadersSpec<?> request(
            WebTestClient http, Route route, String slug, String bearer, UUID id) {
        var request = http.method(HttpMethod.valueOf(route.method().name()))
                .uri(route.path(), slug, id)
                .headers(headers -> headers.setBearerAuth(bearer));
        return switch (route.method()) {
            case PATCH -> request.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("status", "STOPPED"));
            case PUT ->
                request.contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(
                                route.path().endsWith("/read")
                                        ? Map.of("at", Instant.now().toString())
                                        : Map.of("vote", "HELPFUL"));
            case POST ->
                request.header(ClientInputs.HEADER, TimeOrderedIds.next().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("text", "One more question"));
            default -> request;
        };
    }

    private record Route(String path, RequestMethod method) {
        /** Whether the path names a message or a turn after the conversation. */
        boolean namesAnId() {
            return path.substring(CONVERSATION.length() - 1).contains("{");
        }
    }

    private record ConversationIds(UUID turn, UUID question, UUID answer) {
        UUID forRoute(Route route) {
            if (route.path().startsWith(CONVERSATION + "turns/")) return turn;
            if (route.path().startsWith(CONVERSATION + "entries/")) {
                return route.path().endsWith("}") && route.method() == RequestMethod.DELETE ? question : answer;
            }
            throw new IllegalStateException("Add an ID fixture for " + route.path());
        }
    }
}
