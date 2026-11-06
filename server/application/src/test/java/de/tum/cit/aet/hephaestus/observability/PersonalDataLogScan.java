package de.tum.cit.aet.hephaestus.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Scans every log record of a test JVM, encoded as the shipped configuration writes it, for personal data and
 * credentials. It reports only where a match came from: the record's logger, its {@code event.name} and the kind of
 * match, with a count. It never writes the matched text. It runs only with
 * {@code -Dhephaestus.test.personal-data-scan}. With the value {@code fail}, a match fails the run; otherwise it only
 * writes {@code build/reports/personal-data-scan/<pid>.json}.
 */
public final class PersonalDataLogScan implements LauncherSessionListener {

    private static final String MODE = "hephaestus.test.personal-data-scan";

    /** The kinds of personal data and credentials that no record may carry. */
    static final Map<String, Pattern> KINDS = Map.of(
            // A random UUID before the @ is a Message-ID that this code generated, not a person's address.
            "email",
            Pattern.compile("(?<![A-Za-z0-9._%+-])(?![0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@)"
                    + "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"),
            "github_token",
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
            "gitlab_token",
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20}"),
            "slack_token",
            Pattern.compile("\\bxox[abposre]-[A-Za-z0-9-]{10,}"),
            "jwt",
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            "bearer",
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{20,}"));

    /**
     * The person names of the test fixtures, matched as exact tokens. A word list would flood the report with common
     * words; these names exist only because a test wrote them, so a match is a person's name in a record.
     */
    static final List<String> FIXTURE_PEOPLE = List.of(
            "Jordan Admin",
            "Max E. Muster",
            "Sidney Jones",
            "Erika Sample",
            "Test User",
            "Reviewer One",
            "Former Assignee",
            "Context Tester");

    private static final Pattern FIXTURE_PERSON = Pattern.compile("\\b(?:"
            + String.join("|", FIXTURE_PEOPLE.stream().map(Pattern::quote).toList()) + ")\\b");
    private static final Pattern EVENT_NAME = Pattern.compile("\"event\\.name\":\"([^\"]{1,80})\"");

    private final Map<String, LongAdder> findings = new ConcurrentHashMap<>();
    private final LongAdder records = new LongAdder();
    private @Nullable Scanner appender;

    /** The scan of this JVM's launcher session, which {@link Reattach} attaches again. */
    private static volatile @Nullable PersonalDataLogScan current;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        // Only Gradle turns it on: the live suite also runs this listener, inside a container with a read-only disk.
        if (System.getProperty(MODE) == null) return;
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) return;
        install(context);
        current = this;
    }

    void install(LoggerContext context) {
        appender = new Scanner();
        appender.setContext(context);
        attach(context);
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (appender == null) return;
        Map<String, Long> report = new TreeMap<>();
        findings.forEach((key, count) -> report.put(key, count.sum()));
        StringBuilder json =
                new StringBuilder("{\n  \"records\": ").append(records.sum()).append(",\n  \"findings\": {");
        String separator = "\n";
        for (var entry : report.entrySet()) {
            json.append(separator)
                    .append("    \"")
                    .append(entry.getKey().replace("\"", "'"))
                    .append("\": ")
                    .append(entry.getValue());
            separator = ",\n";
        }
        json.append(report.isEmpty() ? "}\n}\n" : "\n  }\n}\n");
        try {
            Path directory = Path.of("build/reports/personal-data-scan");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(ProcessHandle.current().pid() + ".json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!report.isEmpty() && "fail".equals(System.getProperty(MODE))) {
            throw new AssertionError("Log records carried personal data or credentials: " + report.keySet());
        }
    }

    void attach(LoggerContext context) {
        Scanner scanner = appender;
        if (scanner == null) return;
        // A reset stops every appender that it detaches, and a stopped appender drops each event.
        if (!scanner.isStarted()) scanner.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(Scanner.NAME) == null) root.addAppender(scanner);
    }

    long records() {
        return records.sum();
    }

    /** The scan of this JVM, or none outside a launcher session. */
    static @Nullable PersonalDataLogScan current() {
        return current;
    }

    /** {@code logger | event.name | kind}: what the report keys a finding by. */
    static List<String> scan(String logger, String record) {
        var eventName = EVENT_NAME.matcher(record);
        String event = eventName.find() ? eventName.group(1) : "-";
        var kinds = new ArrayList<String>();
        KINDS.forEach((kind, pattern) -> {
            if (pattern.matcher(record).find()) kinds.add(logger + " | " + event + " | " + kind);
        });
        if (FIXTURE_PERSON.matcher(record).find()) kinds.add(logger + " | " + event + " | fixture_person_name");
        return kinds;
    }

    /**
     * Attaches the scan again after Spring Boot configured logging for an application context. Spring Boot stops and
     * resets the logging context first, and a stop removes every Logback listener, so a {@code LoggerContextListener}
     * cannot do this. {@code META-INF/spring.factories} registers it after {@code LoggingApplicationListener}.
     */
    public static final class Reattach implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            PersonalDataLogScan scan = current;
            if (scan != null && LoggerFactory.getILoggerFactory() instanceof LoggerContext context)
                scan.attach(context);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    private final class Scanner extends AppenderBase<ILoggingEvent> {
        static final String NAME = "personal-data-log-scan";
        private @Nullable StructuredLogEncoder encoder;

        Scanner() {
            setName(NAME);
        }

        @Override
        public void start() {
            LoggerContext encoderContext = new LoggerContext();
            encoderContext.putObject(Environment.class.getName(), ShippedLogEncoder.environment("application.yml", -1));
            encoder = new StructuredLogEncoder();
            encoder.setContext(encoderContext);
            encoder.setFormat("ecs");
            encoder.start();
            super.start();
        }

        @Override
        protected void append(ILoggingEvent event) {
            records.increment();
            StructuredLogEncoder started = encoder;
            if (started == null) return;
            String record;
            try {
                record = new String(started.encode(event), StandardCharsets.UTF_8);
            } catch (RuntimeException unreadable) {
                // Logback swallows what an appender throws, so a record that the scan cannot read would pass unseen.
                findings.computeIfAbsent(event.getLoggerName() + " | - | unencoded", ignored -> new LongAdder())
                        .increment();
                return;
            }
            for (String key : scan(event.getLoggerName(), record)) {
                findings.computeIfAbsent(key, ignored -> new LongAdder()).increment();
            }
        }
    }
}
