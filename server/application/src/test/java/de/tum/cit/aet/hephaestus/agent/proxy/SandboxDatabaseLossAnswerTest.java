package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrors;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.AttemptCaptureFailures;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.ErrorResponseException;

/**
 * A result admission that lost this server's database connection answers a sandbox {@code 503}, which the runner
 * retries, so the attempt keeps its work: never the {@code 500} that ends it as the server's own error. The rollback
 * on the lost connection is what reaches the controller, so its cause decides.
 */
class SandboxDatabaseLossAnswerTest extends BaseUnitTest {

    @Test
    void shouldAnswerAnAdmissionWhoseRollbackFoundTheConnectionClosedWithServiceUnavailable() throws Exception {
        var rollback = new JpaSystemException(new RuntimeException(
                "Unable to rollback against JDBC Connection", new SQLException("Connection is closed", "08003")));

        var failures = mock(AttemptCaptureFailures.class);
        var response = admitFailingWith(rollback, failures);

        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(failures);
    }

    @Test
    void shouldAnswerEveryShapeOfALostConnectionWithServiceUnavailable() throws Exception {
        for (RuntimeException lost : List.of(
                new CannotCreateTransactionException("Could not open JPA EntityManager"),
                new JpaSystemException(new RuntimeException(new SQLTransientConnectionException("pool timed out"))),
                new JpaSystemException(new RuntimeException(
                        new SQLException("terminating connection due to crash of another server process", "57P02")))))
            assertThat(admitFailingWith(lost, mock(AttemptCaptureFailures.class))
                            .getStatus())
                    .as(lost.toString())
                    .isEqualTo(503);
    }

    @Test
    void shouldKeepAProtocolViolationTheServersOwnErrorHoweverItIsWrapped() throws Exception {
        var violation = new SQLException("Tried to send an out-of-range integer", "08P01");
        for (RuntimeException wrapped : List.of(
                new JpaSystemException(new RuntimeException(violation)),
                new JpaSystemException(new JDBCConnectionException("could not execute statement", violation)),
                new DataAccessResourceFailureException("could not execute statement", violation)))
            assertThat(admitFailingWith(wrapped, mock(AttemptCaptureFailures.class))
                            .getStatus())
                    .as(wrapped.toString())
                    .isEqualTo(500);
    }

    @Test
    void shouldReadALostConnectionBelowAnExceptionWithoutAState() throws Exception {
        var wrapped = new JpaSystemException(
                new RuntimeException(new SQLException("wrapper", new SQLException("Connection is closed", "08003"))));

        assertThat(admitFailingWith(wrapped, mock(AttemptCaptureFailures.class)).getStatus())
                .isEqualTo(503);
    }

    @Test
    void shouldKeepAnAnswerThatTheRouteAlreadyDecidedWhateverItsCause() throws Exception {
        // As review preparation answers a lost commit reply (ReviewSandboxRuns.preparationUnavailable).
        var decided = new ErrorResponseException(
                HttpStatus.SERVICE_UNAVAILABLE, new TransactionSystemException("The commit reply was lost"));
        decided.getHeaders().set("Retry-After", "1");

        var response = admitFailingWith(decided, mock(AttemptCaptureFailures.class));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void shouldAnswerAnyOtherFailureOfTheSameKindAsTheServersOwnError() throws Exception {
        var unrelated =
                new JpaSystemException(new RuntimeException("not a connection", new SQLException("x", "42P01")));

        assertThat(admitFailingWith(unrelated, mock(AttemptCaptureFailures.class))
                        .getStatus())
                .isEqualTo(500);
    }

    @Test
    void shouldKeepAConstraintConflictAConflict() throws Exception {
        assertThat(admitFailingWith(
                                new DataIntegrityViolationException("duplicate"), mock(AttemptCaptureFailures.class))
                        .getStatus())
                .isEqualTo(409);
    }

    private static MockHttpServletResponse admitFailingWith(RuntimeException failure, AttemptCaptureFailures failures)
            throws Exception {
        var admission = mock(ResultAdmissionService.class);
        when(admission.admit(any(), any())).thenThrow(failure);
        var now = Instant.now();
        var jwt = new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                UUID.randomUUID(),
                1L,
                1,
                UUID.randomUUID(),
                Set.of("llm_proxy"),
                "test",
                now,
                now.plusSeconds(60),
                now.plusSeconds(30));
        return MockMvcBuilders.standaloneSetup(new ResultAdmissionController(admission, failures))
                .setControllerAdvice(new AgentGatewayErrors(), new GlobalControllerAdvice())
                .build()
                .perform(post("/internal/llm/admit-results")
                        .principal(new JobTokenAuthentication(ProxyIdentity.of(jwt), jwt.scopes()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractVersion":3,"reviewId":"%s","jobId":"%s","attempt":0}
                                """.formatted(UUID.randomUUID(), jwt.id())))
                .andReturn()
                .getResponse();
    }
}
