package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionTemplate;

class LlmCallCallerTest extends BaseUnitTest {
    private final LlmCallPermits permits = new LlmCallPermits(
            mock(LlmCallPoolRepository.class),
            mock(LlmCallPermitRepository.class),
            mock(TransactionTemplate.class),
            new LlmCallPoolProperties(Duration.ofSeconds(20), Duration.ofSeconds(30)),
            "caller-test",
            new SimpleMeterRegistry(),
            mock(LlmModelResolver.class));

    @ParameterizedTest
    @EnumSource(value = LlmCallClass.class, names = "PRODUCT_FEEDBACK", mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectAWorkspaceCallWhenItsWorkspaceIsMissing(LlmCallClass kind) {
        var caller = new LlmCallPermits.Caller(null, UUID.randomUUID(), 0, null);
        assertThatThrownBy(() -> permits.acquire(1, kind, caller, () -> false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product feedback calls have no workspace; all other calls require one");
    }

    @Test
    void shouldRejectProductFeedbackWhenItNamesAWorkspace() {
        var caller = new LlmCallPermits.Caller(1L, UUID.randomUUID(), 0, null);
        assertThatThrownBy(() -> permits.acquire(1, LlmCallClass.PRODUCT_FEEDBACK, caller, () -> false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product feedback calls have no workspace; all other calls require one");
    }

    @Test
    void shouldRejectAReviewReservationWhenItsWorkspaceIsMissing() {
        var caller = new LlmCallPermits.Caller(null, UUID.randomUUID(), 0, null);
        assertThatThrownBy(() -> permits.reserve(1, LlmCallClass.REQUEST, caller))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only workspace review calls can reserve capacity");
    }

    @Test
    void shouldRejectAReviewReservationWhenItsClassIsProductFeedback() {
        var caller = new LlmCallPermits.Caller(1L, UUID.randomUUID(), 0, null);
        assertThatThrownBy(() -> permits.reserve(1, LlmCallClass.PRODUCT_FEEDBACK, caller))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only workspace review calls can reserve capacity");
    }

    @Test
    void shouldRejectAClaimWithdrawalWhenItsWorkspaceIsMissing() {
        var caller = new LlmCallPermits.Caller(null, UUID.randomUUID(), 0, null);
        assertThatThrownBy(() -> permits.withdrawClaim(caller))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Review claims require a workspace");
    }
}
