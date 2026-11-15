package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.QueryPlans;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Requests never leave the table, so the reads that look only for active erasures must not scan every receipt. */
class ActiveErasureRequestReadPlanIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PersonDataRequestRepository requests;

    @Autowired
    private IdentityRestrictionService restrictions;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private QueryPlans plans;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @EnumSource(PersonDataRequest.State.class)
    void shouldSuppressAReviewJobOnlyWhileItsErasureIsActive(PersonDataRequest.State state) {
        UUID job = UUID.randomUUID();
        request(state, job);

        assertThat(restrictions.isReviewJobSuppressed(job))
                .isEqualTo(state == PersonDataRequest.State.ERASING || state == PersonDataRequest.State.FAILED);
        assertThat(restrictions.isReviewJobSuppressed(UUID.randomUUID())).isFalse();
    }

    /** A read of many jobs answers for each job what its single check answers, through either kind of selection. */
    @ParameterizedTest
    @EnumSource(PersonDataRequest.State.class)
    void shouldSuppressInOneReadExactlyTheJobsThatTheirSingleChecksSuppress(PersonDataRequest.State state) {
        UUID job = UUID.randomUUID();
        UUID copied = UUID.randomUUID();
        request(
                state,
                "{\"review_job\":{\"rows\":[{\"columns\":{\"id\":\"" + job + "\"}}]},"
                        + "\"person_evidence_copy\":{\"rows\":[{\"columns\":{\"id\":\"" + copied + "\"}}]}}");
        var jobs = List.of(job, copied, UUID.randomUUID());
        boolean active = state == PersonDataRequest.State.ERASING || state == PersonDataRequest.State.FAILED;

        assertThat(restrictions.suppressedReviewJobs(jobs))
                .isEqualTo(jobs.stream()
                        .filter(restrictions::isReviewJobSuppressed)
                        .collect(Collectors.toSet()))
                .isEqualTo(active ? Set.of(job, copied) : Set.of());
        assertThat(restrictions.suppressedReviewJobs(List.of())).isEmpty();
    }

    @Test
    void shouldReadActiveErasuresThroughAnIndexWhenTheTableHoldsReceipts() {
        request(PersonDataRequest.State.COMPLETE, UUID.randomUUID());

        var suppressed = reads.measure(() -> restrictions.isReviewJobSuppressed(UUID.randomUUID()));
        var suppressedJobs =
                reads.measure(() -> restrictions.suppressedReviewJobs(List.of(UUID.randomUUID(), UUID.randomUUID())));
        var interrupted = reads.measure(() -> requests.findInterruptedForPerson(1L, "[]"));

        assertThat(plans.fullScans(suppressed.sql())).isEmpty();
        assertThat(plans.fullScans(suppressedJobs.sql())).isEmpty();
        assertThat(plans.fullScans(interrupted.sql())).isEmpty();
    }

    /** Reaches each state the way the service does; only the preview expiry is SQL. */
    private void request(PersonDataRequest.State state, UUID job) {
        request(state, "{\"review_job\":{\"rows\":[{\"columns\":{\"id\":\"" + job + "\"}}]}}");
    }

    private void request(PersonDataRequest.State state, String selections) {
        var request = new PersonDataRequest();
        request.setSelectionsJson(selections);
        switch (state) {
            case PREVIEW, EXPIRED -> {}
            case ERASING -> request.startErasure(null);
            case FAILED -> {
                request.startErasure(null);
                request.fail("TEST");
            }
            case COMPLETE -> {
                request.startErasure(null);
                request.complete(Instant.now());
            }
        }
        requests.saveAndFlush(request);
        if (state == PersonDataRequest.State.EXPIRED)
            jdbc.update(
                    "UPDATE person_data_request SET state='EXPIRED',scope_json=NULL,selections_json=NULL WHERE id=?",
                    request.getId());
    }
}
