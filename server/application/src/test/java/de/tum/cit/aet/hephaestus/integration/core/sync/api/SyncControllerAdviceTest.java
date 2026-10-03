package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class SyncControllerAdviceTest extends BaseUnitTest {

    private final SyncControllerAdvice advice = new SyncControllerAdvice();

    @Test
    void handleDispatchRejected_mapsTaskRejectedExceptionTo503ProblemDetail() {
        ProblemDetail problem = advice.handleDispatchRejected(new TaskRejectedException("executor saturated"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getTitle()).isEqualTo("Sync dispatch rejected");
        assertThat(problem.getDetail()).contains("busy").contains("Try again");
    }
}
