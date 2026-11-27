package de.tum.cit.aet.hephaestus.practices.review.teamwork;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The journey over HTTP. A method without a route yet fails as a missing step; the pull request that adds the route
 * writes its call here.
 */
final class HttpTeamReviewDriver implements TeamReviewDriver {
    private final WebTestClient http;
    private final String workspace;
    private final String staffToken;

    HttpTeamReviewDriver(WebTestClient http, String workspaceSlug, String staffToken) {
        this.http = http;
        this.workspace = "/workspaces/" + workspaceSlug;
        this.staffToken = staffToken;
    }

    private record Created(UUID id) {}

    @Override
    public UUID createTeamProject(String name) {
        var created = http.post()
                .uri(workspace + "/team-projects")
                .headers(h -> h.setBearerAuth(staffToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(Created.class)
                .returnResult()
                .getResponseBody();
        return Objects.requireNonNull(created).id();
    }

    @Override
    public List<UUID> twoMembers(UUID team) {
        throw unwritten();
    }

    @Override
    public UUID offer(UUID team, long practice) {
        throw unwritten();
    }

    @Override
    public UUID submit(UUID member, UUID setup, Captures.Capture capture, String snapshot, UUID requestKey) {
        throw unwritten();
    }

    @Override
    public UUID requestReview(UUID member, UUID version) {
        throw unwritten();
    }

    @Override
    public void runReview(UUID review, List<Captures.Cited> cited) {
        throw unwritten();
    }

    @Override
    public Read readInApp(UUID member, UUID review) {
        throw unwritten();
    }

    @Override
    public Read readInEditor(UUID member, UUID review) {
        throw unwritten();
    }

    @Override
    public long composerRuns(UUID review) {
        throw unwritten();
    }

    @Override
    public void leave(UUID team, UUID member) {
        throw unwritten();
    }

    @Override
    public int statusForAnOutsider(UUID review) {
        throw unwritten();
    }

    @Override
    public int statusFor(UUID member, UUID review) {
        throw unwritten();
    }

    @Override
    public List<UUID> staffList() {
        throw unwritten();
    }

    private static AssertionError unwritten() {
        return new AssertionError("This step has no code yet");
    }
}
