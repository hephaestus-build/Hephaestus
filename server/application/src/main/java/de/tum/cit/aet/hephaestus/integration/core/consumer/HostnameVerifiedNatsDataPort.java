package de.tum.cit.aet.hephaestus.integration.core.consumer;

import io.nats.client.Connection;
import io.nats.client.impl.SocketDataPort;
import java.io.IOException;
import javax.net.ssl.SSLSocket;

/**
 * jnats 2.26.4 has no hostname option. Replace this class with the upstream
 * {@code tlsVerifyHostname(true)} option when it is published, and remove the builder's
 * hostname-resolution override with it.
 */
public final class HostnameVerifiedNatsDataPort extends SocketDataPort {

    @Override
    public void upgradeToSecure() throws IOException {
        // NatsConnection is package-private; use its public interface.
        Connection active = connection;
        var context = active.getOptions().getSslContext();
        var tlsSocket = (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, true);
        try {
            tlsSocket.setUseClientMode(true);
            var parameters = tlsSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tlsSocket.setSSLParameters(parameters);
            tlsSocket.startHandshake();
            socket = tlsSocket;
            in = tlsSocket.getInputStream();
            out = tlsSocket.getOutputStream();
            isSecure = true;
        } catch (IOException | RuntimeException error) {
            try {
                tlsSocket.close();
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }
}
