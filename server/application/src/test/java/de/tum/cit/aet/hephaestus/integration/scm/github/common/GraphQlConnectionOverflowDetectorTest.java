package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link GraphQlConnectionOverflowDetector}.
 *
 * <p>Pins the level-calibration contract: a count gap that remains after a
 * pagination loop completed normally is benign (DEBUG, no WARN), and WARN is reserved for genuine
 * terminal incompleteness (the loop stopped early, or a single-page embedded connection overflowed).
 */
class GraphQlConnectionOverflowDetectorTest extends BaseUnitTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(GraphQlConnectionOverflowDetector.class);
        logger.setLevel(Level.DEBUG); // ensure the benign DEBUG branch is actually recorded
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    // --- checkPaginated(name, fetched, total, stoppedEarly, context) ---

    @Test
    void paginated_gapAfterFullPagination_logsDebug_returnsFalse() {
        // e.g. reviewThreads where resolved threads contributed no comments, or GitHub over-reports
        boolean overflow =
                GraphQlConnectionOverflowDetector.checkPaginated("reviewThreads", 3, 5, false, "prNumber=42");

        assertThat(overflow).isFalse();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("benign")
                .contains("reviewThreads");
    }

    @Test
    void paginated_gapAfterEarlyStop_logsWarn_returnsTrue() {
        boolean overflow = GraphQlConnectionOverflowDetector.checkPaginated("issues", 30, 100, true, "owner/repo");

        assertThat(overflow).isTrue();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("stopped before all pages");
    }

    @Test
    void paginated_earlyStopButNoGap_logsNothing_returnsFalse() {
        // Stopped early but we still got everything GitHub reported — not incomplete, so no log.
        boolean overflow = GraphQlConnectionOverflowDetector.checkPaginated("issues", 100, 100, true, "owner/repo");

        assertThat(overflow).isFalse();
        assertThat(appender.list).isEmpty();
    }

    // --- single-page embedded overloads (genuine loss, no follow-up pagination) ---

    @Test
    void embedded_totalCountOverflow_logsWarn_returnsTrue() {
        boolean overflow = GraphQlConnectionOverflowDetector.check("assignees", 5, 12, "PR #7");

        assertThat(overflow).isTrue();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("No follow-up pagination");
    }

    @Test
    void embedded_hasNextPage_logsWarn_returnsTrue() {
        boolean overflow = GraphQlConnectionOverflowDetector.check("labels", 100, true, "issue #3");

        assertThat(overflow).isTrue();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void embedded_noOverflow_logsNothing_returnsFalse() {
        assertThat(GraphQlConnectionOverflowDetector.check("assignees", 5, 5, "PR #7"))
                .isFalse();
        assertThat(GraphQlConnectionOverflowDetector.check("labels", 5, false, "issue #3"))
                .isFalse();
        assertThat(appender.list).isEmpty();
    }
}
