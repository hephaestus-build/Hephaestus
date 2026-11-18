package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Review jobs in a stored status, as a row read from the database has it. Production moves a job only through its
 * named transitions and the guarded updates of {@code ReviewJobRepository}; a test that needs a job at some status
 * states the row instead of replaying the run that led there.
 */
public final class ReviewJobFixtures {
    private ReviewJobFixtures() {}

    public static void withStatus(ReviewJob job, ReviewJobStatus status) {
        ReflectionTestUtils.setField(job, "status", status);
    }
}
