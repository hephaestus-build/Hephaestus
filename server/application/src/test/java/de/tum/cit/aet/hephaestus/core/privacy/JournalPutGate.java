package de.tum.cit.aet.hephaestus.core.privacy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A TCP relay in front of the journal bucket that holds the first journal object PUT until the test releases it. It
 * passes every byte through unchanged, so the request keeps the signature the store computed for this endpoint. The
 * bucket's capability probe under {@code _checks/} passes at once.
 */
final class JournalPutGate implements AutoCloseable {

    private static final String JOURNAL_PUT = "PUT /" + JournalMinio.BUCKET + "/" + JournalMinio.PREFIX;

    private final ServerSocket listener;
    private final URI upstream;
    private final CompletableFuture<String> held = new CompletableFuture<>();
    private final CountDownLatch release = new CountDownLatch(1);

    JournalPutGate(URI upstream) throws IOException {
        this.upstream = upstream;
        listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("journal-put-gate").start(this::accept);
    }

    URI endpoint() {
        return URI.create("http://" + listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort());
    }

    /** The request line of the held PUT, once the store sent it. */
    String awaitHeld(Duration timeout) throws Exception {
        return held.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void release() {
        release.countDown();
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                var client = listener.accept();
                var bucket = new Socket(upstream.getHost(), upstream.getPort());
                Thread.ofPlatform().daemon().start(() -> relay(client, bucket, true));
                Thread.ofPlatform().daemon().start(() -> relay(bucket, client, false));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void relay(Socket from, Socket to, boolean towardsBucket) {
        try (InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream()) {
            var buffer = new byte[16 * 1024];
            for (int read; (read = in.read(buffer)) != -1; ) {
                if (towardsBucket) holdTheFirstJournalPut(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException | InterruptedException ended) {
            // Either side closed the connection, or the test ended.
        } finally {
            close(from);
            close(to);
        }
    }

    private void holdTheFirstJournalPut(String bytes) throws InterruptedException {
        int start = bytes.indexOf(JOURNAL_PUT);
        if (start < 0 || bytes.startsWith("_checks/", start + JOURNAL_PUT.length())) return;
        int end = bytes.indexOf("\r\n", start);
        if (held.complete(bytes.substring(start, end < 0 ? bytes.length() : end))) {
            // A test that never releases the PUT lets it pass after 30 seconds, so no request hangs.
            var _ = release.await(30, TimeUnit.SECONDS);
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The socket is closed already.
        }
    }

    @Override
    public void close() throws IOException {
        release();
        listener.close();
    }
}
