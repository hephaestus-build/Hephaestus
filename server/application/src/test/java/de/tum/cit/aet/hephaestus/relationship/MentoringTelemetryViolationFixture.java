package de.tum.cit.aet.hephaestus.relationship;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import java.util.Formatter;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * What a mentoring class must not do, for {@code MentoringLogFieldArchitectureTest}: write a person's text to a log
 * through a library or the JDK, put it on a span, or put it on a meter. Never called.
 */
final class MentoringTelemetryViolationFixture {

    private static final Logger log = LoggerFactory.getLogger(MentoringTelemetryViolationFixture.class);

    private MentoringTelemetryViolationFixture() {}

    static void violate(String message, MeterRegistry meters) throws Exception {
        log.info("The person wrote: {}", message);
        Span.current().setAttribute("account.login", message);
        Counter.builder("mentoring.messages")
                .tag("email", message)
                .register(meters)
                .increment();
        Mono.just(message).log().subscribe();
        System.getLogger("mentoring").log(System.Logger.Level.INFO, message);
        System.LoggerFinder.getLoggerFinder()
                .getLogger("mentoring", MentoringTelemetryViolationFixture.class.getModule())
                .log(System.Logger.Level.INFO, message);
        java.util.logging.Logger.getLogger("mentoring").info(message);
        IO.println(message);
        try (var stderr = new Formatter("/dev/stderr")) {
            stderr.format("%s", message);
        }
        Consumer<Throwable> printTrace = Throwable::printStackTrace;
        printTrace.accept(new IllegalStateException(message));
        Thread.currentThread()
                .getUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), new IllegalStateException(message));
        new ProcessBuilder(message).start();
        Runtime.getRuntime().exec(new String[] {message});
        new ThreadGroup(message);
        ResourceBundle.getBundle(message);
        Appendable err = System.err;
        err.append(message);
        Runnable task = () -> {
            throw new IllegalStateException("fixed text");
        };
        new Thread(task, message).start();
        Thread.currentThread().setName(message);
        Thread.ofVirtual().name(message).start(task);
        new Timer(message);
    }
}
