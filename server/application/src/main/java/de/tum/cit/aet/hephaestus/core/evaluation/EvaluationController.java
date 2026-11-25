package de.tum.cit.aet.hephaestus.core.evaluation;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.core.EvaluationClockStep;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/evaluation")
@Hidden
@ConditionalOnEvaluation
@RequiredArgsConstructor
@RequireInstanceAdmin
@RecentSignInExempt(reason = "Controls isolated scenario time and reads queue state without changing access")
public class EvaluationController {

    private final ControlledClock clock;
    private final EvaluationObserver observer;
    private final EvaluationSnapshot snapshot;
    private final List<EvaluationClockStep> clockSteps;

    record SetClock(@NotNull Instant instant) {}

    record Settle(@Min(1) @Max(120_000) long timeoutMillis) {}

    @PostMapping("/clock")
    @AuditExempt(reason = "Advances only the isolated scenario and its scheduled work")
    public SetClock clock(@Valid @RequestBody SetClock request) {
        clock.set(request.instant());
        observer.changed();
        clockSteps.forEach(step -> step.advance(request.instant()));
        return request;
    }

    @PostMapping("/settle")
    @AuditExempt(reason = "Reads the state of the isolated evaluation instance")
    public EvaluationObserver.Settled settle(@Valid @RequestBody Settle request) throws InterruptedException {
        return observer.await(snapshot, Duration.ofMillis(request.timeoutMillis()));
    }
}
