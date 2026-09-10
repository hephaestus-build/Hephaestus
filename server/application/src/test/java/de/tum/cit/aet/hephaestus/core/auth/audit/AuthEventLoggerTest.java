package de.tum.cit.aet.hephaestus.core.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * Pins the audit-isolation contract: an audit write must never break the caller's business
 * transaction. {@link AuthEventWriter#write} runs {@code REQUIRES_NEW} and swallows the insert
 * failure, but that boundary can still raise {@link UnexpectedRollbackException} at its own commit —
 * which {@link AuthEventLogger#record} must absorb so it never surfaces into the caller.
 */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class AuthEventLoggerTest extends BaseUnitTest {

    @Test
    void recordAbsorbsWriterFailureSoTheCallerTransactionSurvives(
            org.springframework.boot.test.system.CapturedOutput output) {
        AuthEventWriter writer = mock(AuthEventWriter.class);
        doThrow(new UnexpectedRollbackException("inner audit tx was rolled back"))
                .when(writer)
                .write(any());

        AuthEventLogger logger = new AuthEventLogger(writer);

        assertThat(logger.event(AuthEvent.EventType.IDENTITY_UNLINKED, AuthEvent.Result.SUCCESS)
                        .account(1L)
                        .record())
                .isFalse();
        assertThat(output)
                .contains(
                        "auth.audit: IDENTITY_UNLINKED event could not be persisted; suppressed to protect the request");

        verify(writer).write(any());
    }
}
