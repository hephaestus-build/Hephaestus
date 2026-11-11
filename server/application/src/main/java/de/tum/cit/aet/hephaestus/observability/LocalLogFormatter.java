package de.tum.cit.aet.hephaestus.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;

/** A readable local console with the same credential masking, context, and bounded exception printer as JSON. */
public final class LocalLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private final StackTracePrinter stackTracePrinter;
    private final JsonWriter<ILoggingEvent> contextWriter;

    public LocalLogFormatter(
            Environment environment,
            StackTracePrinter stackTracePrinter,
            StructuredLoggingJsonMembersCustomizer.Builder<ILoggingEvent> customizers) {
        this.stackTracePrinter = stackTracePrinter;
        var service = Map.of(
                "name", environment.getRequiredProperty("spring.application.name"),
                "version", environment.getRequiredProperty("logging.structured.ecs.service.version"));
        contextWriter = JsonWriter.of(members -> {
            members.add("service", event -> service);
            customizers.nested().build().customize(members);
        });
    }

    @Override
    public String format(ILoggingEvent event) {
        var text = new StringBuilder();
        text.append(event.getInstant())
                .append(' ')
                .append(event.getLevel())
                .append(' ')
                .append(LoggingUtils.sanitizeForLog(event.getLoggerName()))
                .append(" : ")
                .append(LoggingUtils.sanitizeForLog(CredentialMaskingCustomizer.maskCredentials(
                        Objects.requireNonNullElse(event.getFormattedMessage(), ""))))
                .append(' ')
                .append(LoggingUtils.sanitizeForLog(contextWriter.writeToString(event)))
                .append('\n');
        if (event.getThrowableProxy() instanceof ThrowableProxy proxy) {
            try {
                stackTracePrinter.printStackTrace(proxy.getThrowable(), text);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return text.toString();
    }
}
