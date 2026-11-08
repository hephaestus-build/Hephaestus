package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.result.ResultQueryFilter;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewResultDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultSort;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewResultListReadCostIntegrationTest extends BaseIntegrationTest {
    @Autowired private ReviewIntegrationData data;
    @Autowired private ReviewResultQueryService results;
    @Autowired private SqlReadMeasurement reads;
    @Autowired private SqlRequestTrace requests;
    @Autowired private WebTestClient http;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldKeepOperatorResultListReadsBoundedAsThePageGrows() {
        var scenario = data.scenario();
        var account = Objects.requireNonNull(scenario.account().getId());
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "result-reader", account.toString(), new String[0], "result-read-cost-token"));
        var at = Instant.parse("2026-01-01T00:00:00Z");
        var ids = new ArrayList<UUID>();
        var first = data.result(scenario, ResultOutcome.NOT_MET, at);
        ids.add(first.getId());
        var accepted = data.mark(first, ResultMarkKind.ACCEPTED, account);
        ids.add(data.result(data.onOtherWork(scenario), ResultOutcome.NOT_MET, at.plusSeconds(1)).getId());
        var foreign = data.result(data.scenario(), ResultOutcome.NOT_MET, at);

        var small = reads.measure(() -> results.list(scenario.workspace().getId(), ResultQueryFilter.ALL,
                ResultSort.NEWEST, PageRequest.of(0, 32)));
        assertThat(small.value().getContent()).extracting(ReviewResultDTO::id).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(small.value().getContent()).allSatisfy(row -> {
            assertThat(row.outcome()).isEqualTo(ResultOutcome.NOT_MET);
            assertThat(row.problemCount()).isEqualTo(1);
        });
        assertThat(small.value().getContent().stream().filter(row -> row.id().equals(first.getId())).findFirst()
                .orElseThrow().acceptedAt()).isEqualTo(accepted.getCreatedAt());
        for (int index = 2; index < 8; index++) {
            ids.add(data.result(data.onOtherWork(scenario), ResultOutcome.NOT_MET, at.plusSeconds(index)).getId());
        }

        var larger = reads.measure(() -> results.list(scenario.workspace().getId(), ResultQueryFilter.ALL,
                ResultSort.NEWEST, PageRequest.of(0, 32)));

        assertThat(larger.value().getContent()).extracting(ReviewResultDTO::id)
                .containsExactlyInAnyOrderElementsOf(ids).doesNotContain(foreign.getId());
        assertThat(larger.value().getContent()).containsAll(small.value().getContent());
        assertThat(larger.cost().statements()).as("small=%s, larger=%s", small.cost(), larger.cost())
                .isEqualTo(small.cost().statements());
    }

    @Test
    void shouldKeepOperatorResultRequestCostBoundedAsThePageGrows() {
        var scenario = data.scenario();
        var at = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < 2; index++) {
            data.result(data.onOtherWork(scenario), ResultOutcome.NOT_MET, at.plusSeconds(index));
        }
        var foreign = data.result(data.scenario(), ResultOutcome.NOT_MET, at);
        var path = "/workspaces/" + scenario.workspace().getWorkspaceSlug() + "/practices/reviews/results?size=32";
        var small = requests.measure(http, client -> client.get().uri(path)
                .headers(headers -> headers.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.content.length()").isEqualTo(2));
        for (int index = 2; index < 8; index++) {
            data.result(data.onOtherWork(scenario), ResultOutcome.NOT_MET, at.plusSeconds(index));
        }

        var larger = requests.measure(http, client -> client.get().uri(path)
                .headers(headers -> headers.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.content.length()").isEqualTo(8)
                .jsonPath("$.content[?(@.id=='" + foreign.getId() + "')]").isEmpty());

        assertThat(larger.statements()).as("small=%s, larger=%s", small.statements(), larger.statements())
                .isEqualTo(small.statements());
    }
}
