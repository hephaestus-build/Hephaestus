package de.tum.cit.aet.hephaestus.core.privacy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * A TCP relay in front of the journal bucket that holds the first journal object PUT until the test releases it.
 *
 * <p>TCP can split a request anywhere, so the relay reads each request's head through its blank line before it
 * classifies the request line. It forwards the original bytes, so the request keeps the signature that the store
 * computed for this endpoint, and then the body's {@code Content-Length} bytes. The bucket's capability probe under
 * {@code _checks/} passes at once.
 */
final class JournalPutGate implements AutoCloseable {

    private static final String JOURNAL_PUT = "PUT /" + JournalMinio.BUCKET + "/" + JournalMinio.PREFIX;
    private static final int MAX_HEAD_BYTES = 64 * 1024;

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
                Thread.ofPlatform().daemon().start(() -> requests(client, bucket));
                Thread.ofPlatform().daemon().start(() -> responses(bucket, client));
            } catch (IOException closed) {
                return;
            }
        }
    }

    /** Forwards each request: its head, after the gate classified the complete request line, then its body. */
    private void requests(Socket client, Socket bucket) {
        try (InputStream in = new BufferedInputStream(client.getInputStream());
                OutputStream out = bucket.getOutputStream()) {
            for (byte[] head; (head = head(in)) != null; ) {
                var text = new String(head, StandardCharsets.ISO_8859_1);
                holdTheFirstJournalPut(text.substring(0, text.indexOf("\r\n")));
                out.write(head);
                out.flush();
                long length = bodyLength(text);
                if (length < 0) {
                    // A body without a length has no frame to find the next request in, so the rest passes as is.
                    in.transferTo(out);
                    return;
                }
                copy(in, out, length);
            }
        } catch (IOException | InterruptedException ended) {
            // Either side closed the connection, or the test ended.
        } finally {
            close(client);
            close(bucket);
        }
    }

    private void responses(Socket bucket, Socket client) {
        try (InputStream in = bucket.getInputStream();
                OutputStream out = client.getOutputStream()) {
            var buffer = new byte[16 * 1024];
            for (int read; (read = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ended) {
            // Either side closed the connection.
        } finally {
            close(client);
            close(bucket);
        }
    }

    /** The bytes of one request head through its blank line, or null when the connection ends between requests. */
    private static byte @Nullable [] head(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        int tail = 0;
        for (int next; (next = in.read()) != -1; ) {
            head.write(next);
            tail = (tail << 8) | next;
            if (tail == 0x0d0a0d0a) return head.toByteArray();
            if (head.size() > MAX_HEAD_BYTES) throw new IOException("The request head is too large");
        }
        if (head.size() == 0) return null;
        throw new EOFException("The connection ended inside a request head");
    }

    /** The body's length from the head, zero without one, or -1 when the body is not framed by a length. */
    private static long bodyLength(String head) {
        long length = 0;
        for (var line : head.lines().toList()) {
            var lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("transfer-encoding:")) return -1;
            if (lower.startsWith("content-length:")) {
                length = Long.parseLong(
                        line.substring("content-length:".length()).trim());
            }
        }
        return length;
    }

    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        var buffer = new byte[16 * 1024];
        for (long left = length; left > 0; ) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read == -1) throw new EOFException("The connection ended inside a request body");
            out.write(buffer, 0, read);
            left -= read;
        }
        out.flush();
    }

    private void holdTheFirstJournalPut(String requestLine) throws InterruptedException {
        if (!requestLine.startsWith(JOURNAL_PUT) || requestLine.startsWith("_checks/", JOURNAL_PUT.length())) return;
        if (held.complete(requestLine)) {
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
