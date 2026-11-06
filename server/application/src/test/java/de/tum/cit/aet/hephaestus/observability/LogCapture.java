package de.tum.cit.aet.hephaestus.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/** The records that one class logs while this capture is open. */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(Class<?> source) {
        this(source.getName());
    }

    public LogCapture(String loggerName) {
        logger = (Logger) LoggerFactory.getLogger(loggerName);
        appender.start();
        logger.addAppender(appender);
    }

    /** The fields of each record whose {@code event.name} is {@code name}, in their order. */
    public List<Map<String, String>> events(String name) {
        return List.copyOf(appender.list).stream()
                .filter(event -> event.getKeyValuePairs() != null)
                .map(event -> event.getKeyValuePairs().stream()
                        .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value))))
                .filter(fields -> name.equals(fields.get("event.name")))
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
