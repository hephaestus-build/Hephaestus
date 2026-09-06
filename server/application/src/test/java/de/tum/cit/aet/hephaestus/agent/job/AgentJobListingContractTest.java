package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.AgentJobListRow;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.StuckDeliveryRow;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

/**
 * A review's whole transcript lives on the job row and can run to megabytes. Nothing renders it, so
 * the queries that page over jobs must never read it — a page of entities would, one transcript per
 * row, and the reading is invisible from the endpoint that pays for it.
 */
class AgentJobListingContractTest extends BaseUnitTest {

    @Test
    @DisplayName("the workspace job listing reads rows, not entities, and no row carries the transcript")
    void theListingNeverReadsATranscript() throws NoSuchMethodException {
        Method listing =
                AgentJobRepository.class.getMethod("findListRows", Long.class, AgentJobStatus.class, Pageable.class);

        assertThat(listing.getReturnType()).isEqualTo(Page.class);
        assertThat(((ParameterizedType) listing.getGenericReturnType()).getActualTypeArguments()[0])
                .as("an entity page would select every column, transcript included")
                .isEqualTo(AgentJobListRow.class);
        assertThat(getterNames(AgentJobListRow.class)).doesNotContain("getContainerLogs");
        assertThat(selectClauseOf(listing)).doesNotContain("containerLogs");
    }

    @Test
    @DisplayName("the delivery-recovery sweep reads only what it decides on")
    void theDeliverySweepReadsOnlyWhatItDecidesOn() throws NoSuchMethodException {
        Method sweep = AgentJobRepository.class.getMethod("findStuckPendingDeliveries", Instant.class, Pageable.class);

        assertThat(((ParameterizedType) sweep.getGenericReturnType()).getActualTypeArguments()[0])
                .isEqualTo(StuckDeliveryRow.class);
        assertThat(getterNames(StuckDeliveryRow.class))
                .containsExactlyInAnyOrder("getId", "getDeliveryAttempts", "getDeliveryCommentId");
        assertThat(selectClauseOf(sweep)).doesNotContain("containerLogs");
    }

    /** The query's own text, which is what decides the columns Postgres sends. */
    private static String selectClauseOf(Method method) {
        Query query = method.getAnnotation(Query.class);
        assertThat(query)
                .as("%s must state its own select list", method.getName())
                .isNotNull();
        return query == null ? "" : query.value();
    }

    private static Iterable<String> getterNames(Class<?> projection) {
        return Arrays.stream(projection.getMethods()).map(Method::getName).toList();
    }
}
