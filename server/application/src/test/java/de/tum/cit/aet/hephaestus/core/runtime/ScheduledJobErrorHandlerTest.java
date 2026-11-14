package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/** The next run of a scheduled job retries it: a transient failure is a WARN, any other the run's ERROR. */
class ScheduledJobErrorHandlerTest extends BaseUnitTest {

    @Test
    void shouldLogATransientFailureAtWarnWithoutItsStackTrace() {
        ILoggingEvent record = handle(new DataAccessResourceFailureException(
                "pool exhausted", new SQLTransientConnectionException("connection refused", "08001")));

        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(record.getThrowableProxy()).isNull();
    }

    @Test
    void shouldLogAnyOtherFailureAtErrorWithItsStackTrace() {
        ILoggingEvent record = handle(new IllegalStateException("job defect"));

        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
    }

    private static ILoggingEvent handle(Throwable failure) {
        Logger logger = (Logger) LoggerFactory.getLogger(ScheduledJobErrorHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new ScheduledJobErrorHandler().handleError(failure);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst();
    }
}
