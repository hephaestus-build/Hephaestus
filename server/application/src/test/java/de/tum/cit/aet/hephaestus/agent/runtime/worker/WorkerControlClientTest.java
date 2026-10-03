package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class WorkerControlClientTest extends BaseUnitTest {

    @Test
    void shouldAbortTheReplacedConnectionWhenItsCloseFails() {
        WebSocket ws = mock(WebSocket.class);
        when(ws.sendClose(anyInt(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("Output closed")));

        WorkerControlClient.close(ws, "server-requested:drain");

        verify(ws).abort();
    }

    @Test
    void shouldNotAbortTheReplacedConnectionWhenItClosesCleanly() {
        WebSocket ws = mock(WebSocket.class);
        when(ws.sendClose(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(ws));

        WorkerControlClient.close(ws, "server-requested:drain");

        verify(ws, never()).abort();
    }
}
