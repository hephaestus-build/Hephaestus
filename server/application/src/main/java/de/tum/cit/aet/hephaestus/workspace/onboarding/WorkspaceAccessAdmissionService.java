package de.tum.cit.aet.hephaestus.workspace.onboarding;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipProbe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Provider reads run outside the grant transaction; applying proof rechecks the locked identity and workspace. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceAccessAdmissionService {
    private final WorkspaceAccessAdmissionState state;
    private final List<OrganizationMembershipProbe> probes;
    private final Clock clock;
    private final Cache<WorkspaceAccessAdmissionState.Prepared, Check> checks = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    WorkspaceAccessAdmissionDTO check(Long workspaceId) {
        Long accountId = SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var existing = state.existing(workspaceId, accountId);
        if (existing.isPresent()) return new WorkspaceAccessAdmissionDTO(existing.get());
        var prepared = state.prepare(workspaceId, accountId);
        var check = Objects.requireNonNull(checks.get(prepared, input -> {
            var status = probes.stream()
                    .filter(probe -> probe.kind() == input.kind())
                    .findFirst()
                    .map(probe -> probe.check(input.target()))
                    .orElse(OrganizationMembershipProbe.Status.UNAVAILABLE);
            return new Check(status, clock.instant());
        }));
        return new WorkspaceAccessAdmissionDTO(
                switch (check.status()) {
                    case CONFIRMED -> state.admit(prepared, check.checkedAt());
                    case NOT_CONFIRMED -> State.REQUEST;
                    case UNAVAILABLE -> State.CHECK_UNAVAILABLE;
                });
    }

    enum State {
        ACTIVE,
        REQUEST,
        CHECK_UNAVAILABLE,
        RENEWAL,
        MANAGED
    }

    record WorkspaceAccessAdmissionDTO(@NonNull State state) {}

    private record Check(OrganizationMembershipProbe.Status status, Instant checkedAt) {}
}
