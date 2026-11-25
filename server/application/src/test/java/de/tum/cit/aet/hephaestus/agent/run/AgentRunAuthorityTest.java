package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.core.authority.AuthorizationStamp;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AgentRunAuthorityTest extends BaseUnitTest {

    private static final AgentRunFence FENCE = new AgentRunFence(UUID.randomUUID(), 7, 2);

    @Test
    void shouldRefuseANarrowedRunOnACheckWithoutEndingIt() {
        var runs = mock(AgentRuns.class);
        var run = mock(AgentRun.class);
        when(run.getAuthorizationStamp()).thenReturn(new AuthorizationStamp(0));
        when(runs.find(FENCE.run())).thenReturn(Optional.of(run));
        var generations = mock(AuthorityGenerations.class);
        when(generations.check(any(), anyLong(), any()))
                .thenReturn(Optional.of(new AuthorityRefusal(
                        AuthorityBoundary.CONTEXT_READ, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED)));
        var handler = mock(AgentRunHandler.class);
        var transactions = mock(TransactionTemplate.class);
        var authority =
                new AgentRunAuthority(runs, generations, transactions, new SimpleMeterRegistry(), List.of(handler));

        assertThatThrownBy(() -> authority.check(FENCE, AuthorityBoundary.CONTEXT_READ))
                .isInstanceOf(AuthorityRefusedException.class);
        verifyNoInteractions(transactions, handler);
        verify(runs, never()).end(any(), any());
    }

    @Test
    void shouldLeaveTheDomainUnchangedWhenAParallelRefusalEndedTheRunWhileThisOneWaitedForTheLock() {
        var runs = mock(AgentRuns.class);
        var run = mock(AgentRun.class);
        when(run.getKind()).thenReturn(AgentRunKind.TURN);
        when(run.getAuthorizationStamp()).thenReturn(new AuthorizationStamp(0));
        when(runs.find(FENCE.run())).thenReturn(Optional.of(run));
        // Current before the lock, ended by the other refusal once this one holds it.
        when(runs.isCurrent(FENCE.run(), FENCE.epoch())).thenReturn(true, false);
        var generations = mock(AuthorityGenerations.class);
        when(generations.check(any(), anyLong(), any()))
                .thenReturn(Optional.of(new AuthorityRefusal(
                        AuthorityBoundary.CONTEXT_READ, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED)));
        var handler = mock(AgentRunHandler.class);
        when(handler.kind()).thenReturn(AgentRunKind.TURN);
        var status = mock(TransactionStatus.class);
        var transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any()))
                .thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(status));
        var authority =
                new AgentRunAuthority(runs, generations, transactions, new SimpleMeterRegistry(), List.of(handler));

        assertThatThrownBy(() -> authority.require(FENCE, AuthorityBoundary.CONTEXT_READ))
                .isInstanceOf(StaleRunException.class);
        verify(handler).lock(FENCE.run());
        verify(handler, never()).ended(any(), anyInt(), any());
        verify(runs, never()).end(any(), any());
        verify(status).setRollbackOnly();
    }
}
