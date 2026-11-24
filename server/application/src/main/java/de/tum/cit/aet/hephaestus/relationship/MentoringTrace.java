package de.tum.cit.aet.hephaestus.relationship;

import de.tum.cit.aet.hephaestus.relationship.MentoringFieldClass.Sink;
import io.micrometer.tracing.Tracer;

/** Starts a current span with a declared name and attributes. */
public final class MentoringTrace {

    private final Tracer tracer;

    public MentoringTrace(Tracer tracer) {
        this.tracer = tracer;
    }

    public Scope start(MentoringLogEvent event, MentoringLogField... fields) {
        var builder = tracer.spanBuilder().name(event.eventName());
        for (MentoringLogField field : fields) {
            switch (field.valueFor(Sink.TRACE)) {
                case String text -> builder.tag(field.key(), text);
                case Boolean flag -> builder.tag(field.key(), flag);
                case Integer count -> builder.tag(field.key(), count.longValue());
                case Long number -> builder.tag(field.key(), number.longValue());
                default -> throw new IllegalArgumentException("Unsupported mentoring span attribute");
            }
        }
        var span = builder.start();
        var scope = tracer.withSpan(span);
        return () -> {
            try {
                scope.close();
            } finally {
                span.end();
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
