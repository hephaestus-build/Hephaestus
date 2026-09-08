package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TestAsyncConfigurationTest {

    @Test
    void submittedWorkFinishesOnTheCallingThread() throws Exception {
        var executor = new TestAsyncConfiguration().taskExecutor();
        var executedOn = new AtomicReference<Thread>();

        var result = executor.submit(() -> {
            executedOn.set(Thread.currentThread());
        });

        assertThat(executedOn.get()).isSameAs(Thread.currentThread());
        assertThat(result.isDone()).isTrue();
        assertThat(result.get()).isNull();
    }

    @Test
    void submittedFailuresAreReportedThroughTheFuture() {
        var executor = new TestAsyncConfiguration().taskExecutor();
        var failure = new IllegalStateException("Task failed");
        Runnable task = () -> {
            throw failure;
        };

        var result = executor.submit(task);

        assertThat(result.isDone()).isTrue();
        assertThatThrownBy(result::get).isInstanceOf(ExecutionException.class).hasCause(failure);
        assertThatThrownBy(() -> executor.execute(task)).isSameAs(failure);
    }
}
