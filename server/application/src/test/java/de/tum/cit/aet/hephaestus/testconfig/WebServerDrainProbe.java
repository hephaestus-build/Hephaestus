package de.tum.cit.aet.hephaestus.testconfig;

import java.util.function.BooleanSupplier;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Stands in for the web server's graceful shutdown, which waits for every open request: it records whether a
 * condition held when the context reached that phase.
 */
public final class WebServerDrainProbe implements SmartLifecycle {

    private final BooleanSupplier condition;
    private volatile boolean running;
    private volatile boolean heldAtDrain;

    private WebServerDrainProbe(BooleanSupplier condition) {
        this.condition = condition;
    }

    /**
     * Starts a context that holds {@code bean}, runs {@code whileOpen}, closes the context, and answers whether
     * {@code condition} held when the web server's graceful shutdown would have begun.
     */
    public static <T> boolean heldWhenTheWebServerDrains(
            Class<T> type, T bean, Runnable whileOpen, BooleanSupplier condition) {
        WebServerDrainProbe probe = new WebServerDrainProbe(condition);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
            context.registerBean(type, () -> bean);
            context.registerBean(WebServerDrainProbe.class, () -> probe);
            context.refresh();
            whileOpen.run();
        }
        return probe.heldAtDrain;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        heldAtDrain = condition.getAsBoolean();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE;
    }
}
