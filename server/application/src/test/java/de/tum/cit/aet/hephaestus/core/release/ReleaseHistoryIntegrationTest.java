package de.tum.cit.aet.hephaestus.core.release;

import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.running;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class ReleaseHistoryIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ReleaseStartRepository repository;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private Clock clock;

    @Autowired
    private Environment environment;

    private ReleaseHistory startedAs(RunningRelease release) {
        return new ReleaseHistory(release, repository, transaction, clock, environment);
    }

    private ReleaseHistory startedAs(String version) {
        return startedAs(running(version));
    }

    @Test
    void shouldRecordAReleaseOnceAcrossRestartsAndAgainAfterARollback() {
        startedAs("1.2.3").recordStart();
        startedAs("1.2.3").recordStart();
        startedAs("1.3.0").recordStart();
        startedAs("1.2.3").recordStart();

        assertThat(repository.findTop10ByOrderByIdDesc())
                .extracting(ReleaseStart::getVersion)
                .containsExactly("1.2.3", "1.3.0", "1.2.3");
    }

    @Test
    void shouldRecordTheSameVersionAgainWhenItStartsInAnotherEnvironment() {
        startedAs("1.2.3").recordStart();
        // A database restored from staging into a preview keeps the rows staging recorded.
        startedAs(new RunningRelease(
                        "1.2.3",
                        new ReleaseProperties(ReleaseFixtures.COMMIT, ReleaseFixtures.IMAGE, true, "preview"),
                        new MockEnvironment()))
                .recordStart();

        assertThat(repository.findTop10ByOrderByIdDesc())
                .extracting(ReleaseStart::getEnvironment)
                .containsExactly("preview", ReleaseFixtures.ENVIRONMENT);
    }
}
