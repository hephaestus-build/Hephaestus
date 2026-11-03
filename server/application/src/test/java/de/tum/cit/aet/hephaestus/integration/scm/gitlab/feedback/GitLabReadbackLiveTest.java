package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Posts copies on a real merge request through {@link GitLabLineNoteWriter}, then reads them back as a later attempt
 * does after it lost the receipt. Readback must find a copy whatever footer it carries: the current one, one with
 * another workspace address, and the footer that v1.0.0-rc.1 posted without the disclosure marker. It must not find a
 * copy that was never posted. The test deletes the notes it wrote.
 *
 * <p>Needs {@code READBACK_GITLAB_URL}, {@code READBACK_GITLAB_TOKEN} with the api scope, {@code
 * READBACK_GITLAB_MERGE_REQUEST} ({@code group/project!iid}), and {@code READBACK_PATH} and {@code READBACK_LINE}: a line
 * that the merge request adds. {@code READBACK_GITLAB_CA} names a PEM file to trust for a GitLab behind a private CA.
 */
@Tag("live")
class GitLabReadbackLiveTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void shouldFindEveryPostedCopyWhateverFooterItCarriesWhenTheReceiptIsLost() throws Exception {
        String server = System.getenv("READBACK_GITLAB_URL");
        String token = System.getenv("READBACK_GITLAB_TOKEN");
        String mergeRequest = System.getenv("READBACK_GITLAB_MERGE_REQUEST");
        assumeThat(server).as("READBACK_GITLAB_URL").isNotBlank();
        assumeThat(token).as("READBACK_GITLAB_TOKEN").isNotBlank();
        assumeThat(mergeRequest).as("READBACK_GITLAB_MERGE_REQUEST").isNotBlank();
        String path = Objects.requireNonNull(System.getenv("READBACK_PATH"), "READBACK_PATH");
        int line = Integer.parseInt(Objects.requireNonNull(System.getenv("READBACK_LINE"), "READBACK_LINE"));
        var http = HttpClient.create();
        String ca = System.getenv("READBACK_GITLAB_CA");
        if (ca != null && !ca.isBlank()) {
            var trusted =
                    SslContextBuilder.forClient().trustManager(new File(ca)).build();
            http = http.secure(spec -> spec.sslContext(trusted));
        }
        var connector = new ReactorClientHttpConnector(http);
        var mr = GitLabMrResolver.parseSubjectExternalId(mergeRequest);
        String api = server + "/api/v4/projects/" + URLEncoder.encode(mr.projectPath(), StandardCharsets.UTF_8)
                + "/merge_requests/" + mr.iid();
        var rest = WebClient.builder()
                .clientConnector(connector)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        Map<String, Object> details = Objects.requireNonNull(rest.get()
                .uri(URI.create(api))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(TIMEOUT));
        Map<?, ?> refs = (Map<?, ?>) Objects.requireNonNull(details.get("diff_refs"));
        FeedbackTarget target = ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITLAB, 1L, null),
                mergeRequest,
                String.valueOf(refs.get("base_sha")),
                String.valueOf(refs.get("head_sha")));

        var provider = mock(GitLabGraphQlClientProvider.class);
        when(provider.forScope(anyLong())).thenReturn(graphQl(connector, server, token));
        var tokens = mock(GitLabTokenService.class);
        when(tokens.resolveServerUrl(anyLong())).thenReturn(server);
        when(tokens.getAccessToken(anyLong())).thenReturn(token);
        var builder = WebClient.builder().clientConnector(connector);
        var current = writer(provider, builder, tokens, ScmFeedbackFixtures.formatter("readback"));
        var posted = new ArrayList<CopyReceipt>();
        var keys = new HashSet<String>();
        try {
            var fresh = ScmFeedbackFixtures.readbackPackage("current", path, line);
            fresh.notes().forEach(note -> keys.add(note.writeKey()));
            var written = current.postPackage(target, fresh, (attempting, completed) -> true)
                    .receipts();
            posted.addAll(written);
            assertThat(written).hasSize(3).allMatch(CopyReceipt::acknowledged);
            assertFound(current.findPosted(target, fresh), written);
            assertFound(
                    writer(provider, builder, tokens, ScmFeedbackFixtures.formatter("renamed"))
                            .findPosted(target, fresh),
                    written);

            var old = ScmFeedbackFixtures.readbackPackage("rc.1", path, line);
            old.notes().forEach(note -> keys.add(note.writeKey()));
            var oldWritten = writer(
                            provider, builder, tokens, ScmFeedbackFixtures.formatterBeforeDisclosureMarker("readback"))
                    .postPackage(target, old, (attempting, completed) -> true)
                    .receipts();
            posted.addAll(oldWritten);
            assertThat(oldWritten).hasSize(3).allMatch(CopyReceipt::acknowledged);
            assertFound(current.findPosted(target, old), oldWritten);

            var never = ScmFeedbackFixtures.readbackPackage("never", path, line);
            assertThat(current.findPosted(target, never)).isNotNull().noneMatch(CopyReceipt::acknowledged);
        } finally {
            // A package that did not publish leaves its drafts; remove only the drafts that carry this run's keys.
            List<Map<String, Object>> drafts = Objects.requireNonNull(rest.get()
                    .uri(URI.create(api + "/draft_notes"))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .block(TIMEOUT));
            for (var draft : drafts)
                if (keys.contains(ScmFeedbackFormatter.lastDeliveryKey(String.valueOf(draft.get("note")))))
                    rest.delete()
                            .uri(URI.create(api + "/draft_notes/" + draft.get("id")))
                            .retrieve()
                            .toBodilessEntity()
                            .block(TIMEOUT);
            for (var receipt : posted) {
                String ref = receipt.externalRef();
                if (ref == null) continue;
                rest.delete()
                        .uri(URI.create(api + "/notes/" + ref.substring(ref.lastIndexOf('/') + 1)))
                        .retrieve()
                        .toBodilessEntity()
                        .block(TIMEOUT);
            }
        }
    }

    private static void assertFound(@Nullable List<CopyReceipt> found, List<CopyReceipt> written) {
        assertThat(found).as("readback").isNotNull();
        assertThat(Objects.requireNonNull(found))
                .allMatch(CopyReceipt::acknowledged)
                .extracting(CopyReceipt::copyId, CopyReceipt::externalRef)
                .containsExactlyInAnyOrderElementsOf(written.stream()
                        .map(receipt -> tuple(receipt.copyId(), receipt.externalRef()))
                        .toList());
    }

    private static GitLabLineNoteWriter writer(
            GitLabGraphQlClientProvider provider,
            WebClient.Builder rest,
            GitLabTokenService tokens,
            ScmFeedbackFormatter formatter) {
        var guard = mock(OutboundEgressGuard.class);
        return new GitLabLineNoteWriter(
                provider,
                rest,
                tokens,
                guard,
                ScmFeedbackFixtures.resolver(),
                new GitLabDiffVersionReader(tokens, rest),
                formatter);
    }

    private static HttpGraphQlClient graphQl(ReactorClientHttpConnector connector, String server, String token) {
        var documents = new FragmentMergingDocumentSource(
                List.of(
                        new ClassPathResource("graphql/gitlab/operations/"),
                        new ClassPathResource("graphql/gitlab/fragments/")),
                List.of(".graphql", ".gql"),
                List.of(new ClassPathResource("graphql/gitlab/fragments/GitLabUserFields.graphql")));
        var web = WebClient.builder()
                .clientConnector(connector)
                .baseUrl(server + "/api/graphql")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        return HttpGraphQlClient.builder(web).documentSource(documents).build();
    }
}
