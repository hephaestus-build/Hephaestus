package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.dto.BindPracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PlacePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeAutonomyRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomySource;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class PracticeCatalogControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BASE_URI = "/workspaces/{workspaceSlug}/practices";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private PracticeGroupRepository practiceGroupRepository;

    @Autowired
    private PracticeService practiceService;

    @Autowired
    private PracticeEvidenceDefaults evidenceDefaults;

    private Workspace workspace;

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("catalog-owner");
        workspace = createWorkspace("catalog-ws", "Catalog WS", "catalog-org", AccountType.ORG, owner);
    }

    private Practice persistPractice(String slug, String name, boolean active) {
        Practice practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName(name);
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setCriteria("Detect prompt for " + slug);
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        practice.setAutonomy(active ? PracticeAutonomy.AUTOMATIC : PracticeAutonomy.OFF);
        return practiceRepository.save(practice);
    }

    private PracticeGroup persistGroup(String slug) {
        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug(slug);
        group.setName("Group " + slug);
        return practiceGroupRepository.save(group);
    }

    private Practice persistPractice(String slug, @Nullable PracticeGroup group, int displayOrder) {
        Practice practice = persistPractice(slug, slug, true);
        practice.setGroup(group);
        practice.setDisplayOrder(displayOrder);
        return practiceRepository.save(practice);
    }

    private CreatePracticeRequestDTO validCreateRequest(String slug) {
        return new CreatePracticeRequestDTO(
                slug,
                "Practice " + slug,
                PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_REVIEWED),
                "Detect if the PR follows best practices",
                null,
                PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                null,
                null,
                null);
    }

    private CreatePracticeRequestDTO inGroup(CreatePracticeRequestDTO request, String groupSlug) {
        return new CreatePracticeRequestDTO(
                request.slug(),
                request.name(),
                PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST),
                request.criteria(),
                request.precomputeScript(),
                request.automatedReviewPolicy(),
                request.whyItMatters(),
                request.whatGoodLooksLike(),
                groupSlug);
    }

    private CreatePracticeRequestDTO withEvidence(
            CreatePracticeRequestDTO request, PracticeAutomatedReviewPolicy evidence) {
        boolean automatedReview = evidence.automatedReview().mode() != PracticeAutomatedReviewMode.NONE;
        return new CreatePracticeRequestDTO(
                request.slug(),
                request.name(),
                automatedReview
                        ? PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST)
                        : PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST).stream()
                                .map(binding -> new PracticeBinding(binding.signals(), List.of(), binding.onDrafts()))
                                .toList(),
                request.criteria(),
                automatedReview ? request.precomputeScript() : null,
                evidence,
                request.whyItMatters(),
                request.whatGoodLooksLike(),
                request.groupSlug());
    }

    private static PracticeAutomatedReviewPolicy withoutAutomatedReview(PracticeAutomatedReviewPolicy evidence) {
        return new PracticeAutomatedReviewPolicy(
                evidence.sourceContractVersion(),
                new PracticeAutomatedReview(PracticeAutomatedReviewMode.NONE, PracticeEvidenceSufficiency.NONE),
                evidence.whenEvidenceIsInsufficient(),
                List.of(),
                null);
    }

    private static List<SignalName> signalsOf(PracticeDTO practice) {
        return PracticeBinding.signalsOf(practice.bindings());
    }

    private Consumer<HttpHeaders> withCsrfForAnonymousWrite() {
        return TestAuthUtils.withCsrf(TestAuthUtils.fetchCsrfToken(webTestClient));
    }

    @Test
    @WithAdminUser
    void workspaceAdminCanReadEvidenceAuthoringOptions() {
        ensureAdminMembership(workspace);

        webTestClient
                .get()
                .uri(BASE_URI + "/definition-options", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                // Selected by kind rather than by position: the list is whatever the registered domains
                // declare reviewable, so an index would pin the wrong thing the first time a domain is added.
                .jsonPath("$.workTypes[*].artifactKind")
                .value((java.util.List<String> value) -> org.hamcrest.MatcherAssert.assertThat(
                        value,
                        containsInAnyOrder(
                                "scm.pull_request", "scm.issue", "chat.conversation_thread", "docs.document")))
                .jsonPath("$.workTypes[?(@.artifactKind == 'scm.pull_request')].recommendedNeeds[1].sourceKind")
                .value((java.util.List<String> value) ->
                        org.hamcrest.MatcherAssert.assertThat(value, contains("scm.pull-request.diff")))
                .jsonPath("$.workTypes[?(@.artifactKind == 'scm.pull_request')].allowedSources[0].displayName")
                .value((java.util.List<String> value) ->
                        org.hamcrest.MatcherAssert.assertThat(value, contains("Pull request details")))
                .jsonPath("$.workTypes[?(@.artifactKind == 'scm.pull_request')].allowedSources[0].description")
                .exists();
    }

    @Nested
    class ListPractices {

        @Test
        @WithAdminUser
        void shouldReturnEmptyList() {
            ensureAdminMembership(workspace);

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithAdminUser
        void shouldReturnAllPractices() {
            ensureAdminMembership(workspace);
            persistPractice("alpha", "Alpha", true);
            persistPractice("beta", "Beta", false);

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(2);
        }

        @Test
        @WithAdminUser
        void shouldReturnOrderedByName() {
            ensureAdminMembership(workspace);
            persistPractice("z-slug", "Zebra", true);
            persistPractice("a-slug", "Alpha", true);
            persistPractice("m-slug", "Middle", true);

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(3)
                    .jsonPath("$[0].name")
                    .isEqualTo("Alpha")
                    .jsonPath("$[1].name")
                    .isEqualTo("Middle")
                    .jsonPath("$[2].name")
                    .isEqualTo("Zebra");
        }

        @Test
        @WithAdminUser
        void shouldFilterByUseInNewReviews() {
            ensureAdminMembership(workspace);
            persistPractice("active-one", "Active", true);
            persistPractice("inactive-one", "Inactive", false);

            webTestClient
                    .get()
                    .uri(BASE_URI + "?autonomy=AUTOMATIC", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].slug")
                    .isEqualTo("active-one");
        }

        @Test
        @WithMentorUser
        void shouldRejectMemberToProtectReviewCriteria() {
            User member = persistUser("mentor");
            ensureWorkspaceMembership(workspace, member, WorkspaceMembership.WorkspaceRole.MEMBER);
            persistPractice("member-visible", "Visible", true);

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    @Nested
    class GetPractice {

        @Test
        @WithAdminUser
        @DisplayName("returns practice by slug with all fields")
        void shouldReturnPractice() {
            ensureAdminMembership(workspace);
            persistPractice("target-practice", "Target Practice", true);

            PracticeDTO result = webTestClient
                    .get()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "target-practice")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.slug()).isEqualTo("target-practice");
            assertThat(result.name()).isEqualTo("Target Practice");
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.AUTOMATIC);
            assertThat(result.autonomy().override()).isEqualTo(PracticeAutonomy.AUTOMATIC);
            assertThat(result.autonomy().inherited()).isFalse();
            assertThat(signalsOf(result)).containsExactly(ScmSignals.PULL_REQUEST_OPENED);
            assertThat(result.criteria()).isEqualTo("Detect prompt for target-practice");
            assertThat(result.createdAt()).isNotNull();
            assertThat(result.updatedAt()).isNotNull();
        }

        @Test
        @WithMentorUser
        void shouldRejectMemberToProtectReviewCriteria() {
            User member = persistUser("mentor");
            ensureWorkspaceMembership(workspace, member, WorkspaceMembership.WorkspaceRole.MEMBER);
            persistPractice("member-get", "Member Get", true);

            webTestClient
                    .get()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "member-get")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("returns 404 for non-existent slug")
        void shouldReturn404() {
            ensureAdminMembership(workspace);

            ProblemDetail problem = webTestClient
                    .get()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "non-existent")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getTitle()).isEqualTo("Resource not found");
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "any-slug")
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("POST /practices")
    class CreatePractice {

        @Test
        @WithAdminUser
        @DisplayName("creates practice and returns 201 with location header and all fields")
        void shouldCreatePractice() {
            ensureAdminMembership(workspace);

            PracticeDTO result = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("new-practice"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectHeader()
                    .exists("Location")
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.slug()).isEqualTo("new-practice");
            assertThat(result.name()).isEqualTo("Practice new-practice");
            assertThat(signalsOf(result))
                    .containsExactly(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_REVIEWED);
            assertThat(result.criteria()).isEqualTo("Detect if the PR follows best practices");
            assertThat(result.automatedReviewPolicy())
                    .isEqualTo(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
            // A new practice inherits the fail-closed workspace default until an admin widens it.
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
            assertThat(result.autonomy().override()).isNull();
            assertThat(result.autonomy().source()).isEqualTo(AutonomySource.WORKSPACE);
            assertThat(result.autonomy().inherited()).isTrue();
            assertThat(result.id()).isNotNull();
            assertThat(result.createdAt()).isNotNull();
            assertThat(result.updatedAt()).isNotNull();

            Optional<Practice> persisted =
                    practiceRepository.findByWorkspaceIdAndSlug(workspace.getId(), "new-practice");
            assertThat(persisted).isPresent();
            assertThat(persisted.get().getName()).isEqualTo("Practice new-practice");
            assertThat(persisted.get().getAutonomy()).isNull();
        }

        @Test
        @WithAdminUser
        void shouldCreatePracticeWithMinimalFields() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "minimal-practice",
                    "Minimal Practice",
                    PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED),
                    "Minimal criteria",
                    null,
                    null,
                    null,
                    null,
                    null);

            PracticeDTO result = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.slug()).isEqualTo("minimal-practice");
            assertThat(result.criteria()).isEqualTo("Minimal criteria");
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
            assertThat(result.autonomy().override()).isNull();
            assertThat(result.automatedReviewPolicy())
                    .isEqualTo(evidenceDefaults.policyFor(ArtifactKinds.PULL_REQUEST));
        }

        @Test
        @WithAdminUser
        void shouldCreatePracticeInactiveWithoutAutomatedReview() {
            ensureAdminMembership(workspace);
            CreatePracticeRequestDTO baseline = validCreateRequest("human-assessment-only");
            PracticeAutomatedReviewPolicy policy = baseline.automatedReviewPolicy();
            assertNotNull(policy);
            var request = withEvidence(baseline, withoutAutomatedReview(policy));

            PracticeDTO result = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            // Held on the practice, not inherited: a practice that cannot run a review must stay off
            // whatever its group or its workspace later decides.
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.OFF);
            assertThat(result.autonomy().override()).isEqualTo(PracticeAutonomy.OFF);
        }

        @Test
        @WithAdminUser
        void shouldCreatePracticeInGroup() {
            ensureAdminMembership(workspace);
            persistGroup("review-quality");
            CreatePracticeRequestDTO request = inGroup(validCreateRequest("scoped-practice"), "review-quality");

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody()
                    .jsonPath("$.groupSlug")
                    .isEqualTo("review-quality");

            PracticeGroup group = practiceRepository
                    .findByWorkspaceIdAndSlug(workspace.getId(), "scoped-practice")
                    .orElseThrow()
                    .getGroup();
            assertNotNull(group);
            assertThat(group.getSlug()).isEqualTo("review-quality");
        }

        @Test
        @WithAdminUser
        void shouldReturn404ForGroupFromAnotherWorkspace() {
            ensureAdminMembership(workspace);
            User otherOwner = persistUser("other-catalog-owner");
            Workspace otherWorkspace = createWorkspace(
                    "other-catalog-ws", "Other catalog", "other-catalog-org", AccountType.ORG, otherOwner);
            PracticeGroup foreignGroup = new PracticeGroup();
            foreignGroup.setWorkspace(otherWorkspace);
            foreignGroup.setSlug("foreign-group");
            foreignGroup.setName("Foreign group");
            practiceGroupRepository.save(foreignGroup);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(inGroup(validCreateRequest("scoped-practice"), "foreign-group"))
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);

            assertThat(practiceRepository.findByWorkspaceIdAndSlug(workspace.getId(), "scoped-practice"))
                    .isEmpty();
        }

        @Test
        @WithAdminUser
        void shouldAcceptMinLengthSlug() {
            ensureAdminMembership(workspace);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("abc"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody()
                    .jsonPath("$.slug")
                    .isEqualTo("abc");
        }

        @Test
        @WithAdminUser
        void shouldAcceptMaxLengthSlug() {
            ensureAdminMembership(workspace);
            String slug64 = "a".repeat(64);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest(slug64))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody()
                    .jsonPath("$.slug")
                    .isEqualTo(slug64);
        }

        @Test
        @WithAdminUser
        void shouldReturn409ForDuplicateSlug() {
            ensureAdminMembership(workspace);
            persistPractice("taken-slug", "Existing", true);

            ProblemDetail problem = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("taken-slug"))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getTitle()).isEqualTo("Practice slug conflict");
            assertThat(problem.getDetail()).contains("taken-slug");
        }

        @ParameterizedTest(name = "rejects invalid slug \"{0}\"")
        @MethodSource("invalidSlugs")
        @WithAdminUser
        void shouldReturn400ForInvalidSlug(String badSlug) {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    badSlug,
                    "Name",
                    PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED),
                    null,
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    null,
                    null,
                    null);

            ProblemDetail problem = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getTitle()).isEqualTo("Validation failed");
            assertNotNull(problem.getProperties());
            assertThat(problem.getProperties().get("errors"))
                    .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                    .containsKey("slug");
        }

        static Stream<String> invalidSlugs() {
            return Stream.of("INVALID_SLUG", "bad-slug-", "bad--slug", "-bad-slug", "ab", "a".repeat(65));
        }

        /**
         * An unknown signal is refused by the practice validator, not by bean validation: whether a
         * signal exists is a question only the registered domains can answer, so the answer arrives as
         * a message about the request rather than as a field error.
         */
        @Test
        @WithAdminUser
        void shouldReturn400ForASignalNoDomainDeclares() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "valid-slug",
                    "Name",
                    List.of(new PracticeBinding(
                            List.of(SignalName.of("scm.pull_request.no_such_signal")),
                            PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST),
                            false)),
                    // Everything else valid, or bean validation answers first and the signal is never reached.
                    "Reviewable criteria",
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    null,
                    null,
                    null);

            ProblemDetail problem = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getTitle()).isEqualTo("Invalid workspace request");
            assertThat(problem.getDetail()).isEqualTo("Choose signals declared for the selected work type");
        }

        /**
         * Naming the same signal twice is normalised, not refused.
         *
         * <p>A binding sorts and de-duplicates its signals on construction, because the list is digested
         * into the review-rule fingerprint and two authors writing the same occasion in a different order
         * must not read as two different rules.
         */
        @Test
        @WithAdminUser
        void shouldStoreARepeatedSignalOnce() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "dup-events",
                    "Name",
                    PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_OPENED),
                    "Reviewable criteria",
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    null,
                    null,
                    null);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody()
                    .jsonPath("$.bindings[0].signals")
                    .value((java.util.List<String> value) -> org.hamcrest.MatcherAssert.assertThat(
                            value, contains(ScmSignals.PULL_REQUEST_OPENED.value())));
        }

        @Test
        @WithAdminUser
        void shouldReturn400ForBlankFields() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "",
                    "",
                    PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST),
                    null,
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    null,
                    null,
                    null);

            ProblemDetail problem = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getTitle()).isEqualTo("Validation failed");
            assertNotNull(problem.getProperties());
            assertThat(problem.getProperties().get("errors"))
                    .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                    .containsKeys("slug", "name", "criteria");
        }

        /**
         * The authoring API refuses a second occasion at the request boundary, so the answer names the
         * alternative — split the practice — instead of a generic list-size complaint.
         */
        @Test
        @WithAdminUser
        void shouldReturn400ForASecondOccasion() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "two-occasions",
                    "Two occasions",
                    List.of(
                            PracticeBinding.on(
                                    ScmSignals.PULL_REQUEST_OPENED,
                                    PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST)),
                            PracticeBinding.on(
                                    ScmSignals.PULL_REQUEST_MERGED,
                                    PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST))),
                    "Reviewable criteria",
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    null,
                    null,
                    null);

            ProblemDetail problem = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertNotNull(problem.getProperties());
            assertThat(problem.getProperties().get("errors"))
                    .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                    .hasEntrySatisfying(
                            "bindings",
                            messages -> assertThat(messages)
                                    .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                                    .anySatisfy(
                                            message -> assertThat(message).contains("split this into two practices")));
        }

        @Test
        @WithAdminUser
        void shouldReturn400ForNameTooShort() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "valid-slug",
                    "AB",
                    PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED),
                    null,
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    null,
                    null,
                    null);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void shouldReturn400ForEmptyTriggerEvents() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "no-events",
                    "Name",
                    PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST),
                    null,
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    null,
                    null,
                    null);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void shouldReturn403ForNonAdmin() {
            User memberUser = persistUser("mentor");
            ensureWorkspaceMembership(workspace, memberUser, WorkspaceMembership.WorkspaceRole.MEMBER);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("forbidden-practice"))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @DisplayName("rejects anonymous create (403 via CSRF gate, before auth)")
        void shouldRejectAnonymousCreate() {
            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("anon-practice"))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("PATCH /practices/{practiceSlug}")
    class UpdatePractice {

        @Test
        @WithAdminUser
        @DisplayName("partially updates practice (only name)")
        void shouldPartiallyUpdate() {
            ensureAdminMembership(workspace);
            Practice practice = persistPractice("update-me", "Original Name", true);
            practice.setGroup(persistGroup("existing-group"));
            practiceRepository.save(practice);

            var request = new UpdatePracticeRequestDTO("Updated Name", null, null, null, null, null, null, null, null);

            PracticeDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "update-me")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Updated Name");
            assertThat(signalsOf(result)).containsExactly(ScmSignals.PULL_REQUEST_OPENED);
            assertThat(result.criteria()).isEqualTo("Detect prompt for update-me");
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.AUTOMATIC);
            assertThat(result.groupSlug()).isEqualTo("existing-group");
        }

        @Test
        @WithAdminUser
        void shouldResolveEvidenceWhenArtifactChanges() {
            ensureAdminMembership(workspace);
            persistPractice("change-artifact", "Change Artifact", true);
            var request = new UpdatePracticeRequestDTO(
                    null,
                    PracticeTestEvidence.bindings(ScmSignals.ISSUE_OPENED),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            PracticeDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "change-artifact")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.artifactKind()).isEqualTo(ArtifactKinds.ISSUE);
            assertThat(result.automatedReviewPolicy()).isEqualTo(evidenceDefaults.policyFor(ArtifactKinds.ISSUE));
        }

        @Test
        @WithAdminUser
        void shouldStopUsingPracticeInNewReviewsWhenAutomatedReviewIsRemoved() {
            ensureAdminMembership(workspace);
            Practice practice = persistPractice("remove-automated-review", "Remove assessment", true);
            PracticeAutomatedReviewPolicy requirements = withoutAutomatedReview(practice.getAutomatedReviewPolicy());
            var request = new UpdatePracticeRequestDTO(null, null, null, null, requirements, null, null, null, null);

            PracticeDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), practice.getSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.OFF);
            assertThat(result.autonomy().override()).isEqualTo(PracticeAutonomy.OFF);
            // The occasion survives — it is where the practice's kind comes from — but a practice
            // nobody automates reads nothing, so the evidence goes with the automation that read it.
            assertThat(signalsOf(result)).containsExactly(ScmSignals.PULL_REQUEST_OPENED);
            assertThat(result.bindings())
                    .allSatisfy(binding -> assertThat(binding.needs()).isEmpty());
            assertThat(practiceRepository
                            .findByWorkspaceIdAndSlug(workspace.getId(), practice.getSlug())
                            .orElseThrow()
                            .getAutonomy())
                    .isEqualTo(PracticeAutonomy.OFF);
        }

        @Test
        @WithAdminUser
        @DisplayName("updates definition and placement atomically")
        void updatesDefinitionAndPlacementAtomically() {
            ensureAdminMembership(workspace);
            persistPractice("full-update", "Old Name", true);
            persistGroup("target-group");

            var request = new UpdatePracticeRequestDTO(
                    "New Name",
                    PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_REVIEWED),
                    "New prompt",
                    null,
                    null,
                    null,
                    null,
                    new BindPracticeGroupRequestDTO("target-group"),
                    null);

            PracticeDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "full-update")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("New Name");
            assertThat(signalsOf(result)).containsExactly(ScmSignals.PULL_REQUEST_REVIEWED);
            assertThat(result.criteria()).isEqualTo("New prompt");
            assertThat(result.groupSlug()).isEqualTo("target-group");

            Optional<Practice> persisted =
                    practiceRepository.findByWorkspaceIdAndSlug(workspace.getId(), "full-update");
            assertThat(persisted).isPresent();
            assertThat(persisted.get().getName()).isEqualTo("New Name");
            assertThat(persisted.get().getCriteria()).isEqualTo("New prompt");
            PracticeGroup group = persisted.get().getGroup();
            assertNotNull(group);
            assertThat(group.getSlug()).isEqualTo("target-group");
            PracticeRevision revision = practiceRevisionRepository
                    .findFirstByPracticeIdOrderByRevisionNumberDesc(
                            persisted.get().getId())
                    .orElseThrow();
            assertThat(revision.getName()).isEqualTo("New Name");
            assertThat(revision.getCriteria()).isEqualTo("New prompt");
            assertThat(revision.getGroupSlug()).isEqualTo("target-group");
            assertThat(revision.getGroupName()).isEqualTo("Group target-group");
        }

        @Test
        @WithAdminUser
        void rejectsMissingGroupWithoutChangingDefinition() {
            ensureAdminMembership(workspace);
            persistPractice("atomic-update", "Original Name", true);

            var request = new UpdatePracticeRequestDTO(
                    "Changed Name",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new BindPracticeGroupRequestDTO("missing-group"),
                    null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "atomic-update")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);

            Practice persisted = practiceRepository
                    .findByWorkspaceIdAndSlug(workspace.getId(), "atomic-update")
                    .orElseThrow();
            assertThat(persisted.getName()).isEqualTo("Original Name");
            assertThat(persisted.getGroup()).isNull();
        }

        @Test
        @WithAdminUser
        void explicitlyUnassignsPractice() {
            ensureAdminMembership(workspace);
            Practice practice = persistPractice("unassign-in-patch", "Unassign", true);
            practice.setGroup(persistGroup("current-group"));
            practiceRepository.save(practice);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "unassign-in-patch")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"group\":{\"groupSlug\":null}}")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.groupSlug")
                    .doesNotExist();
        }

        @Test
        @WithAdminUser
        void unassignsWhenNestedGroupSlugIsOmitted() {
            ensureAdminMembership(workspace);
            Practice practice = persistPractice("omitted-group-patch", "Omitted group", true);
            practice.setGroup(persistGroup("current-group"));
            practiceRepository.save(practice);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "omitted-group-patch")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"group\":{}}")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.groupSlug")
                    .doesNotExist();
        }

        @Test
        @WithAdminUser
        void clearsOptionalContent() {
            ensureAdminMembership(workspace);
            Practice practice = persistPractice("clear-content", "Clear content", true);
            practice.setPrecomputeScript("return {}");
            practice.setWhyItMatters("Useful rationale");
            practice.setWhatGoodLooksLike("Useful example");
            practiceRepository.save(practice);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "clear-content")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                    {"clear":["PRECOMPUTE_SCRIPT","WHY_IT_MATTERS","WHAT_GOOD_LOOKS_LIKE"]}
                    """)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.precomputeScript")
                    .doesNotExist()
                    .jsonPath("$.whyItMatters")
                    .doesNotExist()
                    .jsonPath("$.whatGoodLooksLike")
                    .doesNotExist();
        }

        @Test
        @WithAdminUser
        @DisplayName("returns 404 for non-existent slug")
        void shouldReturn404() {
            ensureAdminMembership(workspace);

            var request = new UpdatePracticeRequestDTO("Name", null, null, null, null, null, null, null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "non-existent")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void shouldReturn400ForNameTooShort() {
            ensureAdminMembership(workspace);
            persistPractice("bad-update", "Name", true);

            var request = new UpdatePracticeRequestDTO("AB", null, null, null, null, null, null, null, null);

            ProblemDetail problem = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "bad-update")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getTitle()).isEqualTo("Validation failed");
            assertNotNull(problem.getProperties());
            assertThat(problem.getProperties().get("errors"))
                    .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                    .containsKey("name");
        }

        @Test
        @WithAdminUser
        void shouldReturn400ForWhitespaceOnlyName() {
            ensureAdminMembership(workspace);
            persistPractice("ws-name", "Name", true);

            var request = new UpdatePracticeRequestDTO("   ", null, null, null, null, null, null, null, null);

            ProblemDetail problem = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "ws-name")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getTitle()).isEqualTo("Validation failed");
            assertNotNull(problem.getProperties());
            assertThat(problem.getProperties().get("errors"))
                    .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                    .containsKey("name");
        }

        @Test
        @WithAdminUser
        void shouldReturn400ForWhitespaceOnlyCriteria() {
            ensureAdminMembership(workspace);
            persistPractice("ws-criteria", "Name", true);

            var request = new UpdatePracticeRequestDTO(null, null, "   ", null, null, null, null, null, null);

            ProblemDetail problem = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "ws-criteria")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getTitle()).isEqualTo("Validation failed");
            assertNotNull(problem.getProperties());
            assertThat(problem.getProperties().get("errors"))
                    .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                    .containsKey("criteria");
        }

        @Test
        @WithAdminUser
        void shouldReturn400ForInvalidTriggerEventsInUpdate() {
            ensureAdminMembership(workspace);
            persistPractice("update-events", "Name", true);

            var request = new UpdatePracticeRequestDTO(
                    null,
                    List.of(new PracticeBinding(
                            List.of(SignalName.of("scm.pull_request.no_such_signal")),
                            PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST),
                            false)),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "update-events")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void shouldReturn403ForNonAdmin() {
            User memberUser = persistUser("mentor");
            ensureWorkspaceMembership(workspace, memberUser, WorkspaceMembership.WorkspaceRole.MEMBER);
            persistPractice("forbidden-update", "Name", true);

            var request = new UpdatePracticeRequestDTO("New Name", null, null, null, null, null, null, null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "forbidden-update")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            var request = new UpdatePracticeRequestDTO("Name", null, null, null, null, null, null, null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "any-slug")
                    .headers(withCsrfForAnonymousWrite())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("PUT /practices/{practiceSlug}/group")
    class BindGroup {

        @Test
        @WithAdminUser
        void shouldUnassignWithExplicitNull() {
            ensureAdminMembership(workspace);
            Practice practice = persistPractice("unassign-me", "Unassign me", true);
            practice.setGroup(persistGroup("current-group"));
            practiceRepository.save(practice);

            webTestClient
                    .put()
                    .uri(BASE_URI + "/{slug}/group", workspace.getWorkspaceSlug(), "unassign-me")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"groupSlug\":null}")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.groupSlug")
                    .doesNotExist();

            assertThat(practiceRepository
                            .findByWorkspaceIdAndSlug(workspace.getId(), "unassign-me")
                            .orElseThrow()
                            .getGroup())
                    .isNull();
        }

        @Test
        @WithAdminUser
        void shouldUnassignWhenGroupSlugIsOmitted() {
            ensureAdminMembership(workspace);
            Practice practice = persistPractice("omitted-group", "Omitted group", true);
            practice.setGroup(persistGroup("current-group"));
            practiceRepository.save(practice);

            webTestClient
                    .put()
                    .uri(BASE_URI + "/{slug}/group", workspace.getWorkspaceSlug(), "omitted-group")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.groupSlug")
                    .doesNotExist();
        }
    }

    @Nested
    @DisplayName("PUT /practices/{practiceSlug}/placement")
    class PlacePractice {

        @Test
        @WithAdminUser
        void movesBetweenGroupsAtTheRequestedPosition() {
            ensureAdminMembership(workspace);
            PracticeGroup source = persistGroup("source");
            PracticeGroup destination = persistGroup("destination");
            persistPractice("alpha", source, 0);
            persistPractice("bravo", source, 1);
            persistPractice("charlie", source, 2);
            persistPractice("delta", destination, 0);
            persistPractice("echo", destination, 1);

            List<PracticeDTO> result = webTestClient
                    .put()
                    .uri(BASE_URI + "/{slug}/placement", workspace.getWorkspaceSlug(), "bravo")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new PlacePracticeRequestDTO("destination", 1))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBodyList(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(slugsIn(result, "source")).containsExactly("alpha", "charlie");
            assertThat(slugsIn(result, "destination")).containsExactly("delta", "bravo", "echo");
            Practice moved = practiceRepository
                    .findByWorkspaceIdAndSlug(workspace.getId(), "bravo")
                    .orElseThrow();
            PracticeRevision revision = practiceRevisionRepository
                    .findFirstByPracticeIdOrderByRevisionNumberDesc(moved.getId())
                    .orElseThrow();
            assertThat(revision.getGroupSlug()).isEqualTo("destination");
            assertThat(revision.getGroupName()).isEqualTo("Group destination");
        }

        @Test
        @WithAdminUser
        void supportsEmptyGroupsAndUnassigned() {
            ensureAdminMembership(workspace);
            PracticeGroup source = persistGroup("source");
            persistGroup("empty");
            persistPractice("movable", source, 0);

            place("movable", new PlacePracticeRequestDTO("empty", 0))
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].groupSlug")
                    .isEqualTo("empty")
                    .jsonPath("$[0].displayOrder")
                    .isEqualTo(0);

            place("movable", new PlacePracticeRequestDTO(null, 0))
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].groupSlug")
                    .doesNotExist()
                    .jsonPath("$[0].displayOrder")
                    .isEqualTo(0);
        }

        @Test
        @WithAdminUser
        void reordersWithinAnGroup() {
            ensureAdminMembership(workspace);
            PracticeGroup group = persistGroup("group");
            persistPractice("alpha", group, 0);
            persistPractice("bravo", group, 1);
            persistPractice("charlie", group, 2);

            List<PracticeDTO> result = place("charlie", new PlacePracticeRequestDTO("group", 0))
                    .expectStatus()
                    .isOk()
                    .expectBodyList(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(slugsIn(result, "group")).containsExactly("charlie", "alpha", "bravo");
        }

        @Test
        @WithMentorUser
        void requiresWorkspaceAdmin() {
            User member = persistUser("mentor");
            ensureWorkspaceMembership(workspace, member, WorkspaceMembership.WorkspaceRole.MEMBER);
            persistPractice("protected", null, 0);

            place("protected", new PlacePracticeRequestDTO(null, 0))
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void rejectsInvalidOrIncompletePlacementWithoutChangingTheCatalog() {
            ensureAdminMembership(workspace);
            PracticeGroup group = persistGroup("group");
            persistPractice("alpha", group, 0);
            persistPractice("bravo", group, 1);

            place("alpha", new PlacePracticeRequestDTO("group", 2))
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
            place("alpha", new PlacePracticeRequestDTO("missing", 0))
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
            place("alpha", "{\"groupSlug\":\"group\"}")
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);

            List<Practice> persisted = practiceRepository.findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(
                    workspace.getId(), group.getId());
            assertThat(persisted).extracting(Practice::getSlug).containsExactly("alpha", "bravo");
        }

        private WebTestClient.ResponseSpec place(String slug, Object request) {
            return webTestClient
                    .put()
                    .uri(BASE_URI + "/{slug}/placement", workspace.getWorkspaceSlug(), slug)
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange();
        }

        private List<String> slugsIn(@Nullable List<PracticeDTO> practices, String groupSlug) {
            assertNotNull(practices);
            return practices.stream()
                    .filter(practice -> groupSlug.equals(practice.groupSlug()))
                    .sorted(java.util.Comparator.comparing(PracticeDTO::displayOrder))
                    .map(PracticeDTO::slug)
                    .toList();
        }
    }

    @Nested
    @DisplayName("PATCH /practices/{practiceSlug}/autonomy")
    class SetUsedInNewReviews {

        /**
         * Every rung is settable on a practice, and setting one is the practice's own decision. HUMAN_APPROVAL is
         * in the list on purpose: it is the only way to turn one practice down without turning its
         * measurement off, so refusing it here would make the autonomy a boolean again. The seed is always the
         * opposite end of the ladder, so every case is a real change rather than a re-send of the autonomy
         * already in force.
         */
        @ParameterizedTest
        @EnumSource(PracticeAutonomy.class)
        @WithAdminUser
        @DisplayName("stores any autonomy as the practice's own and reports the practice as the source")
        void shouldSetThePracticesOwnAutonomy(PracticeAutonomy autonomy) {
            ensureAdminMembership(workspace);
            persistPractice("retier-me", "Name", autonomy == PracticeAutonomy.OFF);

            PracticeDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}/autonomy", workspace.getWorkspaceSlug(), "retier-me")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(autonomy))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.autonomy().effective()).isEqualTo(autonomy);
            assertThat(result.autonomy().override()).isEqualTo(autonomy);
            assertThat(result.autonomy().source()).isEqualTo(AutonomySource.PRACTICE);

            Optional<Practice> persisted = practiceRepository.findByWorkspaceIdAndSlug(workspace.getId(), "retier-me");
            assertThat(persisted).isPresent();
            assertThat(persisted.get().getAutonomy()).isEqualTo(autonomy);
        }

        @Test
        @WithAdminUser
        void shouldRejectActivationWithoutSupportedAutomatedReview() {
            ensureAdminMembership(workspace);
            Practice practice = persistPractice("no-automated-review", "No automated review", false);
            practice.setAutomatedReviewPolicy(withoutAutomatedReview(practice.getAutomatedReviewPolicy()));
            practiceRepository.save(practice);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}/autonomy", workspace.getWorkspaceSlug(), "no-automated-review")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.AUTOMATIC))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("is idempotent when the practice is already used in new reviews")
        void shouldBeIdempotent() {
            ensureAdminMembership(workspace);
            persistPractice("already-active", "Name", true);

            PracticeDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}/autonomy", workspace.getWorkspaceSlug(), "already-active")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.AUTOMATIC))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.AUTOMATIC);
        }

        @Test
        @WithAdminUser
        @DisplayName("returns 404 for non-existent slug")
        void shouldReturn404() {
            ensureAdminMembership(workspace);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}/autonomy", workspace.getWorkspaceSlug(), "non-existent")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.OFF))
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void shouldReturn403ForNonAdmin() {
            User memberUser = persistUser("mentor");
            ensureWorkspaceMembership(workspace, memberUser, WorkspaceMembership.WorkspaceRole.MEMBER);
            persistPractice("forbidden-toggle", "Name", true);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}/autonomy", workspace.getWorkspaceSlug(), "forbidden-toggle")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.OFF))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        /**
         * A null autonomy is the only way back out of an override. Without it the chain would be write-once:
         * an administrator who set one practice explicitly could never return it to its group's decision.
         */
        @Test
        @WithAdminUser
        @DisplayName("a null autonomy clears the practice's own setting and it inherits again")
        void shouldClearTheOverrideOnNullTier() {
            ensureAdminMembership(workspace);
            PracticeGroup group = persistGroup("group-with-a-autonomy");
            group.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
            practiceGroupRepository.save(group);
            Practice practice = persistPractice("null-active", "Name", true);
            practice.setGroup(group);
            practiceRepository.save(practice);

            PracticeDTO result = webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}/autonomy", workspace.getWorkspaceSlug(), "null-active")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"autonomy\": null}")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.autonomy().override()).isNull();
            assertThat(result.autonomy().effective()).isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
            assertThat(result.autonomy().source()).isEqualTo(AutonomySource.GROUP);
            assertThat(result.autonomy().inherited()).isTrue();
            assertThat(practiceRepository
                            .findByWorkspaceIdAndSlug(workspace.getId(), "null-active")
                            .orElseThrow()
                            .getAutonomy())
                    .isNull();
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}/autonomy", workspace.getWorkspaceSlug(), "any-slug")
                    .headers(withCsrfForAnonymousWrite())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdatePracticeAutonomyRequestDTO(PracticeAutonomy.OFF))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    @Nested
    class DeletePractice {

        @Test
        @WithAdminUser
        @DisplayName("deletes a practice and verifies removal from database")
        void shouldDeletePractice() {
            ensureAdminMembership(workspace);
            persistPractice("to-delete", "Delete Me", true);

            webTestClient
                    .delete()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "to-delete")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectBody(Void.class);

            Optional<Practice> persisted = practiceRepository.findByWorkspaceIdAndSlug(workspace.getId(), "to-delete");
            assertThat(persisted).isEmpty();
        }

        @Test
        @WithAdminUser
        @DisplayName("returns 404 for non-existent slug")
        void shouldReturn404() {
            ensureAdminMembership(workspace);

            webTestClient
                    .delete()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "non-existent")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void shouldReturn403ForNonAdmin() {
            User memberUser = persistUser("mentor");
            ensureWorkspaceMembership(workspace, memberUser, WorkspaceMembership.WorkspaceRole.MEMBER);
            persistPractice("forbidden-delete", "Name", true);

            webTestClient
                    .delete()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "forbidden-delete")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            webTestClient
                    .delete()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "any-slug")
                    .headers(withCsrfForAnonymousWrite())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }

    @Nested
    class WorkspaceIsolation {

        @Test
        @WithAdminUser
        @DisplayName("practice from workspace A is not accessible via workspace B")
        void shouldIsolateReads() {
            User ownerA = persistUser("owner-a");
            User ownerB = persistUser("owner-b");
            Workspace wsA = createWorkspace("ws-a", "WS A", "org-a", AccountType.ORG, ownerA);
            Workspace wsB = createWorkspace("ws-b", "WS B", "org-b", AccountType.ORG, ownerB);
            ensureAdminMembership(wsA);
            ensureAdminMembership(wsB);

            Practice practice = new Practice();
            practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
            practice.setWorkspace(wsA);
            practice.setSlug("isolated-practice");
            practice.setName("Isolated");
            practice.setCriteria("Description");
            practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
            practiceRepository.save(practice);

            webTestClient
                    .get()
                    .uri(BASE_URI + "/{slug}", wsA.getWorkspaceSlug(), "isolated-practice")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            webTestClient
                    .get()
                    .uri(BASE_URI + "/{slug}", wsB.getWorkspaceSlug(), "isolated-practice")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("list returns empty when practices only exist in other workspace")
        void shouldIsolateList() {
            User ownerA = persistUser("list-owner-a");
            User ownerB = persistUser("list-owner-b");
            Workspace wsA = createWorkspace("list-ws-a", "A", "list-org-a", AccountType.ORG, ownerA);
            Workspace wsB = createWorkspace("list-ws-b", "B", "list-org-b", AccountType.ORG, ownerB);
            ensureAdminMembership(wsA);
            ensureAdminMembership(wsB);

            Practice practice = new Practice();
            practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
            practice.setWorkspace(wsA);
            practice.setSlug("only-in-a");
            practice.setName("Only in A");
            practice.setCriteria("Description");
            practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
            practiceRepository.save(practice);

            webTestClient
                    .get()
                    .uri(BASE_URI, wsB.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithAdminUser
        void shouldAllowSameSlugInDifferentWorkspace() {
            User ownerA = persistUser("create-owner-a");
            User ownerB = persistUser("create-owner-b");
            Workspace wsA = createWorkspace("create-ws-a", "A", "create-org-a", AccountType.ORG, ownerA);
            Workspace wsB = createWorkspace("create-ws-b", "B", "create-org-b", AccountType.ORG, ownerB);
            ensureAdminMembership(wsA);
            ensureAdminMembership(wsB);

            webTestClient
                    .post()
                    .uri(BASE_URI, wsA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("shared-slug"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            webTestClient
                    .post()
                    .uri(BASE_URI, wsB.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("shared-slug"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("cannot update practice via wrong workspace")
        void shouldIsolateWrites() {
            User ownerA = persistUser("write-owner-a");
            User ownerB = persistUser("write-owner-b");
            Workspace wsA = createWorkspace("write-ws-a", "A", "write-org-a", AccountType.ORG, ownerA);
            Workspace wsB = createWorkspace("write-ws-b", "B", "write-org-b", AccountType.ORG, ownerB);
            ensureAdminMembership(wsA);
            ensureAdminMembership(wsB);

            Practice practice = new Practice();
            practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
            practice.setWorkspace(wsA);
            practice.setSlug("write-isolated");
            practice.setName("Write Isolated");
            practice.setCriteria("Desc");
            practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
            practiceRepository.save(practice);

            var request = new UpdatePracticeRequestDTO("Hacked Name", null, null, null, null, null, null, null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", wsB.getWorkspaceSlug(), "write-isolated")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("Practice definition revision history")
    class DefinitionVersioning {

        private List<PracticeRevision> revisionsFor(String slug) {
            Long practiceId = practiceRepository
                    .findByWorkspaceIdAndSlug(workspace.getId(), slug)
                    .orElseThrow()
                    .getId();
            return practiceRevisionRepository.findAll().stream()
                    .filter(r -> r.getPractice().getId().equals(practiceId))
                    .sorted((a, b) -> Integer.compare(a.getRevisionNumber(), b.getRevisionNumber()))
                    .toList();
        }

        @Test
        @WithAdminUser
        @DisplayName("create appends revision 1 snapshotting the complete definition")
        void createAppendsRevisionOne() {
            ensureAdminMembership(workspace);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("versioned-practice"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            List<PracticeRevision> revisions = revisionsFor("versioned-practice");
            assertThat(revisions).hasSize(1);
            assertThat(revisions.get(0).getRevisionNumber()).isEqualTo(1);
            assertThat(revisions.get(0).getSlug()).isEqualTo("versioned-practice");
            assertThat(revisions.get(0).getName()).isEqualTo("Practice versioned-practice");
            assertThat(revisions.get(0).getArtifactKind()).isEqualTo(ArtifactKinds.PULL_REQUEST);
            assertThat(revisions.get(0).getBindings())
                    .singleElement()
                    .extracting(PracticeBinding::signals, as(InstanceOfAssertFactories.list(SignalName.class)))
                    .containsExactly(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_REVIEWED);
            assertThat(revisions.get(0).getCriteria()).isEqualTo("Detect if the PR follows best practices");
            assertThat(revisions.get(0).getReviewRuleFingerprint()).hasSize(67).startsWith("v3:");
            assertThat(revisions.get(0).getCreatedAt()).isNotNull();
        }

        @Test
        @WithAdminUser
        @DisplayName("update with CHANGED criteria appends revision 2")
        void changedCriteriaAppendsRevisionTwo() {
            ensureAdminMembership(workspace);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("evolving-practice"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            var request = new UpdatePracticeRequestDTO(
                    null, null, "A revised detection rubric", null, null, null, null, null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "evolving-practice")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            List<PracticeRevision> revisions = revisionsFor("evolving-practice");
            assertThat(revisions).hasSize(2);
            assertThat(revisions)
                    .extracting(PracticeRevision::getRevisionNumber)
                    .containsExactly(1, 2);
            assertThat(revisions.get(0).getCriteria()).isEqualTo("Detect if the PR follows best practices");
            assertThat(revisions.get(1).getCriteria()).isEqualTo("A revised detection rubric");
        }

        @Test
        @WithAdminUser
        @DisplayName("renaming a practice appends a complete new revision")
        void renameAppendsRevision() {
            ensureAdminMembership(workspace);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("stable-practice"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            var request =
                    new UpdatePracticeRequestDTO("Renamed Practice", null, null, null, null, null, null, null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "stable-practice")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            List<PracticeRevision> revisions = revisionsFor("stable-practice");
            assertThat(revisions).hasSize(2);
            assertThat(revisions)
                    .extracting(PracticeRevision::getRevisionNumber)
                    .containsExactly(1, 2);
            assertThat(revisions)
                    .extracting(PracticeRevision::getName)
                    .containsExactly("Practice stable-practice", "Renamed Practice");
        }

        @Test
        @WithAdminUser
        @DisplayName("update sending the SAME criteria value appends no new revision")
        void identicalCriteriaValueAppendsNoRevision() {
            ensureAdminMembership(workspace);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(validCreateRequest("noop-criteria-practice"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            var request = new UpdatePracticeRequestDTO(
                    null, null, "Detect if the PR follows best practices", null, null, null, null, null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "noop-criteria-practice")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            assertThat(revisionsFor("noop-criteria-practice")).hasSize(1);
        }

        @Test
        @DisplayName(
                "two criteria edits racing on the same practice both persist with distinct numbers (no poisoned tx)")
        void concurrentCriteriaEditsBothPersistDistinctNumbers() throws Exception {
            persistPractice("raced-practice", "Raced", true);
            var ctx = WorkspaceContext.fromWorkspace(workspace, Set.of(WorkspaceMembership.WorkspaceRole.ADMIN), null);

            practiceService.updatePractice(
                    ctx,
                    "raced-practice",
                    new UpdatePracticeRequestDTO(null, null, "baseline criteria", null, null, null, null, null, null));

            int threads = 2;
            var startGate = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            var pool = Executors.newFixedThreadPool(threads);
            var failures = Collections.synchronizedList(new ArrayList<Throwable>());

            try {
                for (int i = 0; i < threads; i++) {
                    final String criteria = "concurrent edit " + i;
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            practiceService.updatePractice(
                                    ctx,
                                    "raced-practice",
                                    new UpdatePracticeRequestDTO(
                                            null, null, criteria, null, null, null, null, null, null));
                        } catch (Throwable t) {
                            failures.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                startGate.countDown();
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(failures)
                    .as("no concurrent edit should throw UnexpectedRollbackException")
                    .isEmpty();
            List<PracticeRevision> revisions = revisionsFor("raced-practice");
            assertThat(revisions)
                    .extracting(PracticeRevision::getRevisionNumber)
                    .doesNotHaveDuplicates()
                    .containsExactly(1, 2, 3);
        }
    }

    /**
     * A practice's artifact kind is read off its signals, so a pull-request signal makes it a
     * pull-request practice and there is no separately declared focus left to contradict. What can still
     * be wrong is naming signals about two different kinds of work at once.
     */
    @Nested
    @DisplayName("A practice's kind of work comes from its signals")
    class ArtifactKindFromSignals {

        @Test
        @WithAdminUser
        @DisplayName("a pull-request signal makes it a pull-request practice, whatever the policy names")
        void readsTheKindOffTheSignalRatherThanTheDeclaredPolicy() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "kind-from-signal",
                    "Kind From Signal",
                    PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_REVIEWED),
                    "Review something",
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.ISSUE),
                    null,
                    null,
                    null);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody()
                    .jsonPath("$.artifactKind")
                    .isEqualTo(ArtifactKinds.PULL_REQUEST.value());

            assertThat(practiceRepository
                            .findByWorkspaceIdAndSlug(workspace.getId(), "kind-from-signal")
                            .orElseThrow()
                            .getArtifactKind())
                    .isEqualTo(ArtifactKinds.PULL_REQUEST);
        }

        @Test
        @WithAdminUser
        @DisplayName("one practice cannot be about two kinds of work at once → 400")
        void refusesBindingsThatDisagreeAboutTheKindOfWork() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "two-minds",
                    "Two Minds",
                    List.of(
                            PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED)
                                    .getFirst(),
                            PracticeTestEvidence.bindings(ScmSignals.ISSUE_OPENED)
                                    .getFirst()),
                    "Review something",
                    null,
                    null,
                    null,
                    null,
                    null);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);

            assertThat(practiceRepository.findByWorkspaceIdAndSlug(workspace.getId(), "two-minds"))
                    .isEmpty();
        }

        @Test
        @WithAdminUser
        void createsScheduledConversationPracticeWithoutTriggerEvents() {
            ensureAdminMembership(workspace);
            var request = new CreatePracticeRequestDTO(
                    "conversation-practice",
                    "Conversation Practice",
                    PracticeTestEvidence.bindings(ArtifactKinds.CONVERSATION_THREAD),
                    "Detect constructive conversations",
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.CONVERSATION_THREAD),
                    null,
                    null,
                    null);

            PracticeDTO result = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.artifactKind()).isEqualTo(ArtifactKinds.CONVERSATION_THREAD);
            // No ingested event raises it — a scheduler decides a thread has settled — but the occasion
            // is still declared, so the catalog says what started the review.
            assertThat(signalsOf(result)).containsExactly(ChatSignals.CONVERSATION_THREAD_SETTLED);
        }
    }

    @Nested
    @DisplayName("GET /practices/developer — anti-leak projection")
    class ReviewedPracticeProjection {

        @Test
        @WithAdminUser
        @DisplayName("raw JSON omits criteria but carries why-it-matters and what-good-looks-like")
        void developerViewHidesCriteriaExposesRationale() {
            ensureAdminMembership(workspace);

            var request = new CreatePracticeRequestDTO(
                    "developer-practice",
                    "Developer Practice",
                    PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED),
                    "INTERNAL detection rubric — must never reach a developer",
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    "Small, focused PRs are easier to review.",
                    "A PR that changes one thing and explains why in the description.",
                    null);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            String rawJson = webTestClient
                    .get()
                    .uri(BASE_URI + "/reviewed", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(rawJson).isNotNull();
            assertThat(rawJson).doesNotContain("\"criteria\"");
            assertThat(rawJson).doesNotContain("INTERNAL detection rubric");
            assertThat(rawJson).contains("whyItMatters");
            assertThat(rawJson).contains("whatGoodLooksLike");
            assertThat(rawJson).contains("Small, focused PRs are easier to review.");
            assertThat(rawJson).contains("A PR that changes one thing and explains why in the description.");
        }
    }

    @Nested
    @DisplayName("Authoring guard on whatGoodLooksLike")
    class AuthoringGuard {

        private CreatePracticeRequestDTO createWithExemplar(String slug, String whatGoodLooksLike) {
            return new CreatePracticeRequestDTO(
                    slug,
                    "Guard Practice",
                    PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED),
                    "Detect prompt",
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    "Why it matters.",
                    whatGoodLooksLike,
                    null);
        }

        @Test
        @WithAdminUser
        @DisplayName("create with PRESENT in whatGoodLooksLike → 400")
        void rejectsPresentTokenOnCreate() {
            ensureAdminMembership(workspace);

            ProblemDetail problem = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(createWithExemplar("guard-present", "The error handler is PRESENT in every case."))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(ProblemDetail.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getStatus()).isEqualTo(400);
            assertThat(problem.getTitle()).isEqualTo("Invalid workspace request");
            assertThat(practiceRepository.findByWorkspaceIdAndSlug(workspace.getId(), "guard-present"))
                    .isEmpty();
        }

        @Test
        @WithAdminUser
        @DisplayName("create with GOOD/BAD/ABSENT in whatGoodLooksLike → 400")
        void rejectsAssessmentTokensOnCreate() {
            ensureAdminMembership(workspace);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(createWithExemplar("guard-assessment", "Flagged GOOD, BAD, or ABSENT by the detector."))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("create with detector vocab in whyItMatters → 400 (whyItMatters is also developer-facing)")
        void rejectsVocabInWhyItMatters() {
            ensureAdminMembership(workspace);

            var dto = new CreatePracticeRequestDTO(
                    "guard-why",
                    "Guard Practice",
                    PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED),
                    "Detect prompt",
                    null,
                    PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                    "The error handler is PRESENT in every case.",
                    "A clean exemplar.",
                    null);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(dto)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
            assertThat(practiceRepository.findByWorkspaceIdAndSlug(workspace.getId(), "guard-why"))
                    .isEmpty();
        }

        @Test
        @WithAdminUser
        @DisplayName("create with NOT_APPLICABLE in whatGoodLooksLike → 400")
        void rejectsNotApplicableTokenOnCreate() {
            ensureAdminMembership(workspace);

            webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(createWithExemplar("guard-not-applicable", "Marked NOT_APPLICABLE here."))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("update that introduces PRESENT into whatGoodLooksLike → 400")
        void rejectsPresentTokenOnUpdate() {
            ensureAdminMembership(workspace);
            persistPractice("guard-update", "Guard Update", true);

            var request = new UpdatePracticeRequestDTO(
                    null, null, null, null, null, null, "This behaviour is PRESENT.", null, null);

            webTestClient
                    .patch()
                    .uri(BASE_URI + "/{slug}", workspace.getWorkspaceSlug(), "guard-update")
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("lowercase English assessment words remain valid")
        void acceptsLowercaseEnglishAssessmentWords() {
            ensureAdminMembership(workspace);

            PracticeDTO result = webTestClient
                    .post()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                            createWithExemplar(
                                    "guard-clean",
                                    "A good PR description states the problem, the change, and how it was verified, so a bad surprise is avoided."))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(PracticeDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.slug()).isEqualTo("guard-clean");
        }
    }
}
