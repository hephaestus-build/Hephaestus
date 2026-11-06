package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.engine.jdbc.spi.SqlExceptionHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * Hibernate logs the driver's message for each failed statement under its own category, apart from the exception the
 * caller receives. PostgreSQL's message quotes the rejected row values.
 */
@Tag("unit")
class SqlErrorLoggingTest {

    private static final String CATEGORY = "org.hibernate.orm.jdbc.error";

    @Test
    void shouldSilenceTheDriverMessageThatHibernateLogsForAFailedStatement() throws IOException {
        SQLException failure = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uk_account_email\"\n"
                        + "  Detail: Key (email)=(rejected-row-value) already exists.",
                "23505");
        Logger logger = (Logger) LoggerFactory.getLogger(CATEGORY);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // Unbounded, the helper writes the address under this category: the category name is the real one.
            logger.setLevel(Level.WARN);
            new SqlExceptionHelper(true).logExceptions(failure, "insert account");
            assertThat(messages(appender)).anyMatch(message -> message.contains("rejected-row-value"));

            appender.list.clear();
            logger.setLevel(Level.toLevel(shippedLevel()));
            new SqlExceptionHelper(true).logExceptions(failure, "insert account");
            assertThat(appender.list).isEmpty();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String shippedLevel() throws IOException {
        Object level = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst()
                .getProperty("logging.level." + CATEGORY);
        assertThat(level).as("application.yml bounds " + CATEGORY).isEqualTo("OFF");
        return String.valueOf(level);
    }
}
