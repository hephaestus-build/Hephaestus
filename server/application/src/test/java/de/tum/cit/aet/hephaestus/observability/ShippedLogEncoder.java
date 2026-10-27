package de.tum.cit.aet.hephaestus.observability;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** Boot's ECS encoder, configured from the shipped {@code application*.yml} as every runtime role configures it. */
public final class ShippedLogEncoder {

    private ShippedLogEncoder() {}

    /** The record that the shipped base configuration writes for {@code event}. */
    public static String encode(ILoggingEvent event) {
        return encode(environment("application.yml", -1), event);
    }

    public static String encode(Environment environment, ILoggingEvent event) {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(((LoggerContext) LoggerFactory.getILoggerFactory()).getMDCAdapter());
        context.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        try {
            return new String(encoder.encode(event), StandardCharsets.UTF_8);
        } finally {
            encoder.stop();
            context.stop();
        }
    }

    /**
     * The base document of application.yml, with one more document on top: {@code -1} adds none, any other index adds
     * that document of {@code file}, as its profile would.
     */
    public static StandardEnvironment environment(String file, int document) {
        try {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            List<PropertySource<?>> sources =
                    new ArrayList<>(loader.load("application.yml", new ClassPathResource("application.yml")));
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(sources.getFirst());
            if (document >= 0) {
                environment
                        .getPropertySources()
                        .addFirst(loader.load(file, new ClassPathResource(file)).get(document));
            }
            return environment;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
