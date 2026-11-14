package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Applies the source control egress check to Git fetches. JGit resolves names with the JVM, which no resolver hook
 * reaches, so the check reads the connected peer address when the TLS handshake asks to trust the server. That is
 * after the socket connects and before the request, and its token, is sent: a refused address gets a TCP connection
 * and a TLS greeting, never a request.
 */
@Component
public class GuardedGitTransport {
    private final Factory factory;
    private final ScmServerEndpointPolicy endpoints;

    public GuardedGitTransport(ScmServerEndpointPolicy endpoints) {
        this.factory = new Factory(endpoints, defaultTrust());
        this.endpoints = endpoints;
    }

    /**
     * The check for one fetch from {@code cloneUrl}, set on the transport that JGit chose. A Git configuration such as
     * {@code url.<base>.insteadOf} can rewrite the URL, and it
     * gets the guarded connections whatever factory JGit holds globally. The transport must reach exactly the requested
     * URL, so a rewrite, or a configured remote named like the URL, is refused.
     */
    public TransportConfigCallback callback(String cloneUrl) {
        return transport -> {
            URIish requested;
            try {
                requested = new URIish(cloneUrl);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Git fetch refused: the clone URL is not valid", e);
            }
            URIish actual = transport.getURI();
            // Any rewrite, even to another port or path on the same host, could send the token to another service.
            // URIish compares decoded paths, so the raw path must match too: "team%2Frepo.git" is not "team/repo.git".
            if (!actual.equals(requested)
                    || !String.valueOf(actual.getRawPath()).equals(requested.getRawPath()))
                throw new IllegalStateException("Git fetch refused: the Git configuration rewrote the URL");
            // URIish gives a lowercase scheme, or none for a path or the scp form host:path.
            String scheme = requested.getScheme();
            if (!"https".equals(scheme)) {
                // Only the server's own mirror (a local path) and the e2e simulator are not HTTPS. Every other scheme,
                // and the scp form host:path, would reach a host that the egress check never sees.
                boolean local = requested.getHost() == null && (scheme == null || "file".equals(scheme));
                boolean simulator = "http".equals(scheme)
                        && endpoints.isSimulationOrigin(scheme, requested.getHost(), requested.getPort());
                if (!local && !simulator)
                    throw new IllegalStateException("Git fetch refused: a provider fetch must use HTTPS");
                // A configured upload-pack command runs in a shell; the default one runs inside JGit.
                transport.setOptionUploadPack(RemoteConfig.DEFAULT_UPLOAD_PACK);
                return;
            }
            if (!(transport instanceof TransportHttp http))
                throw new IllegalStateException("Git fetch refused: an HTTPS URL must use the HTTPS transport");
            http.setHttpConnectionFactory(factory.forOrigin(requested));
        };
    }

    static X509ExtendedTrustManager defaultTrust() {
        try {
            var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            return Arrays.stream(factory.getTrustManagers())
                    .filter(X509ExtendedTrustManager.class::isInstance)
                    .map(X509ExtendedTrustManager.class::cast)
                    .findFirst()
                    .orElseThrow();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JVM has no default trust manager", e);
        }
    }

    static final class Factory implements HttpConnectionFactory2 {
        private final HttpConnectionFactory2 delegate;
        private final ScmServerEndpointPolicy endpoints;
        private final X509ExtendedTrustManager trust;

        Factory(ScmServerEndpointPolicy endpoints, X509ExtendedTrustManager trust) {
            this(new JDKHttpConnectionFactory(), endpoints, trust);
        }

        private final @Nullable URIish origin;

        Factory(HttpConnectionFactory2 delegate, ScmServerEndpointPolicy endpoints, X509ExtendedTrustManager trust) {
            this(delegate, endpoints, trust, null);
        }

        private Factory(
                HttpConnectionFactory2 delegate,
                ScmServerEndpointPolicy endpoints,
                X509ExtendedTrustManager trust,
                @Nullable URIish origin) {
            this.delegate = delegate;
            this.endpoints = endpoints;
            this.trust = trust;
            this.origin = origin;
        }

        /** Connections for one fetch: a redirect that a Git configuration turned on cannot leave the requested origin. */
        Factory forOrigin(URIish requested) {
            return new Factory(delegate, endpoints, trust, requested);
        }

        private void requireOrigin(URL url) throws IOException {
            if (origin == null) return;
            int port = origin.getPort() > 0 ? origin.getPort() : 443;
            int actual = url.getPort() > 0 ? url.getPort() : url.getDefaultPort();
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || !url.getHost().equalsIgnoreCase(String.valueOf(origin.getHost()))
                    || actual != port) throw new IOException("Git fetch refused: a redirect left the requested origin");
        }

        @Override
        public HttpConnection create(URL url) throws IOException {
            requireOrigin(url);
            return guarded(delegate.create(url), Proxy.NO_PROXY);
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws IOException {
            requireOrigin(url);
            return guarded(delegate.create(url, proxy), proxy);
        }

        /** A session that would turn certificate checks off would also replace the peer check, so it is refused. */
        @Override
        public GitSession newSession() {
            var session = delegate.newSession();
            return new GitSession() {
                @Override
                public HttpConnection configure(HttpConnection connection, boolean sslVerify)
                        throws IOException, GeneralSecurityException {
                    if (!sslVerify) throw new IOException("Git fetch refused: certificate verification is off");
                    return guarded(session.configure(connection, true), Proxy.NO_PROXY);
                }

                @Override
                public void close() {
                    session.close();
                }
            };
        }

        private HttpConnection guarded(HttpConnection connection, Proxy proxy) throws IOException {
            // Through a proxy the socket reaches the proxy, which resolves the host itself, so no peer address is ours
            // to check.
            if (proxy.type() != Proxy.Type.DIRECT)
                throw new IOException("Git fetch refused: a proxy would choose the address that it connects to");
            try {
                connection.configure(null, new TrustManager[] {new PeerCheck(endpoints, trust)}, null);
            } catch (GeneralSecurityException e) {
                throw new IOException("The Git connection could not be secured", e);
            }
            return connection;
        }
    }

    /** Trusts what the JVM trusts, once the connected peer is an address that the policy admits. */
    static final class PeerCheck extends X509ExtendedTrustManager {
        private final ScmServerEndpointPolicy endpoints;
        private final X509ExtendedTrustManager trust;

        PeerCheck(ScmServerEndpointPolicy endpoints, X509ExtendedTrustManager trust) {
            this.endpoints = endpoints;
            this.trust = trust;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            InetAddress peer = socket.getInetAddress();
            String host = socket instanceof SSLSocket ssl && ssl.getHandshakeSession() != null
                    ? String.valueOf(ssl.getHandshakeSession().getPeerHost())
                    : peer == null ? "" : peer.getHostAddress();
            String refusal = peer == null ? "the connection has no peer address" : endpoints.refusal(host, peer);
            if (refusal != null) throw new CertificateException("Git fetch refused: " + refusal);
            trust.checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("Git fetch refused: the connection has no socket to check");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Git fetch refused: the connection has no socket to check");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            trust.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            trust.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            trust.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return trust.getAcceptedIssuers();
        }
    }
}
