package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.ScmEgressProperties;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

/** A Git fetch resolves through the JVM, so the check reads the connected peer before the token is sent. */
class GuardedGitTransportTest extends BaseUnitTest {
    private static final X509Certificate[] CHAIN = new X509Certificate[0];

    private static ScmServerEndpointPolicy endpoints(String... listed) {
        return new ScmServerEndpointPolicy(
                new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of(listed)));
    }

    private static SSLSocket connectedTo(String host, String address) throws Exception {
        var socket = mock(SSLSocket.class);
        var session = mock(SSLSession.class);
        when(socket.getInetAddress()).thenReturn(InetAddress.getByName(address));
        when(socket.getHandshakeSession()).thenReturn(session);
        when(session.getPeerHost()).thenReturn(host);
        return socket;
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "169.254.169.254", "::ffff:169.254.169.254", "10.9.9.9", "fe80::1"})
    void shouldRefuseAFetchWhoseConnectionReachedANonPublicAddress(String address) throws Exception {
        var trust = mock(X509ExtendedTrustManager.class);
        var check = new GuardedGitTransport.PeerCheck(endpoints(), trust);

        assertThatThrownBy(() -> check.checkServerTrusted(CHAIN, "RSA", connectedTo("gitlab.example.com", address)))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("Git fetch refused");
        verifyNoInteractions(trust);
    }

    @ParameterizedTest
    @ValueSource(strings = {"169.254.169.254", "127.0.0.1", "100.100.100.200"})
    void shouldRefuseAListedNameThatReachedLoopbackOrMetadata(String address) throws Exception {
        var check = new GuardedGitTransport.PeerCheck(
                endpoints("gitlab.corp.example"), mock(X509ExtendedTrustManager.class));

        assertThatThrownBy(() -> check.checkServerTrusted(CHAIN, "RSA", connectedTo("gitlab.corp.example", address)))
                .isInstanceOf(CertificateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.43.8", "10.20.30.40"})
    void shouldTrustWhatTheJvmTrustsOnceThePeerIsAdmitted(String address) throws Exception {
        var trust = mock(X509ExtendedTrustManager.class);
        var check = new GuardedGitTransport.PeerCheck(endpoints("gitlab.corp.example"), trust);
        var socket = connectedTo("gitlab.corp.example", address);

        assertThatCode(() -> check.checkServerTrusted(CHAIN, "RSA", socket)).doesNotThrowAnyException();
        verify(trust).checkServerTrusted(CHAIN, "RSA", socket);
    }

    @Test
    void shouldRefuseAHandshakeWithoutASocketToCheck() {
        var check = new GuardedGitTransport.PeerCheck(endpoints(), mock(X509ExtendedTrustManager.class));

        assertThatThrownBy(() -> check.checkServerTrusted(CHAIN, "RSA")).isInstanceOf(CertificateException.class);
    }

    @Test
    void shouldRefuseAFetchThroughAProxyWhichChoosesItsOwnAddress() {
        var factory = new GuardedGitTransport.Factory(
                mock(HttpConnectionFactory2.class), endpoints(), mock(X509ExtendedTrustManager.class));
        var proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 3128));

        assertThatThrownBy(() -> factory.create(
                        URI.create("https://gitlab.example.com/a.git").toURL(), proxy))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("proxy");
    }

    @Test
    void shouldRefuseASessionThatTurnsCertificateChecksOff() throws Exception {
        var delegate = mock(HttpConnectionFactory2.class);
        when(delegate.newSession()).thenReturn(mock(HttpConnectionFactory2.GitSession.class));
        var session = new GuardedGitTransport.Factory(delegate, endpoints(), mock(X509ExtendedTrustManager.class))
                .newSession();

        assertThatThrownBy(() -> session.configure(mock(HttpConnection.class), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("certificate verification is off");
    }

    @Test
    void shouldKeepThePeerCheckAfterTheSessionConfiguresTheConnection() throws Exception {
        var delegate = mock(HttpConnectionFactory2.class);
        var jgitSession = mock(HttpConnectionFactory2.GitSession.class);
        var connection = mock(HttpConnection.class);
        when(delegate.newSession()).thenReturn(jgitSession);
        when(jgitSession.configure(connection, true)).thenReturn(connection);
        var session = new GuardedGitTransport.Factory(delegate, endpoints(), mock(X509ExtendedTrustManager.class))
                .newSession();

        session.configure(connection, true);

        var trust = ArgumentCaptor.forClass(TrustManager[].class);
        verify(connection).configure(isNull(), trust.capture(), isNull());
        assertThat(trust.getValue()).singleElement().isInstanceOf(GuardedGitTransport.PeerCheck.class);
    }

    /** A Git configuration can rewrite the URL to another transport, which the guarded factory would never see. */
    @Test
    void shouldRefuseAFetchThatTheGitConfigurationRewroteToAnotherTransportOrHost() throws Exception {
        var callback = GitTransportFixtures.guarded().callback("https://gitlab.example.com/team/repo.git");
        var anonymous = mock(Transport.class);
        when(anonymous.getURI()).thenReturn(new URIish("git://127.0.0.1:9418/team/repo.git"));
        var otherHost = mock(TransportHttp.class);
        when(otherHost.getURI()).thenReturn(new URIish("https://127.0.0.1/team/repo.git"));

        var otherPort = mock(TransportHttp.class);
        when(otherPort.getURI()).thenReturn(new URIish("https://gitlab.example.com:8443/steal/team/repo.git"));
        var mirror = GitTransportFixtures.guarded().callback("file:///data/git-repos/mirrors/1/2.git/");
        var aliased = mock(TransportHttp.class);
        when(aliased.getURI()).thenReturn(new URIish("http://127.0.0.1:8080/probe.git"));

        assertThatThrownBy(() -> callback.configure(anonymous)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> callback.configure(otherHost)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> callback.configure(otherPort)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mirror.configure(aliased)).isInstanceOf(IllegalStateException.class);
        var encoded = mock(TransportHttp.class);
        when(encoded.getURI()).thenReturn(new URIish("https://gitlab.example.com/team%2Frepo.git"));
        assertThatThrownBy(() -> callback.configure(encoded)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldGiveAnHttpsFetchTheGuardedConnections() throws Exception {
        var http = mock(TransportHttp.class);
        when(http.getURI()).thenReturn(new URIish("https://gitlab.example.com/team/repo.git"));

        GitTransportFixtures.guarded()
                .callback("https://gitlab.example.com/team/repo.git")
                .configure(http);

        verify(http).setHttpConnectionFactory(any(GuardedGitTransport.Factory.class));
    }

    @Test
    void shouldRunALocalFetchWithTheBuiltInUploadPackWhateverTheConfigurationSays() throws Exception {
        var local = mock(Transport.class);
        when(local.getURI()).thenReturn(new URIish("file:///data/git-repos/mirrors/1/2.git/"));

        GitTransportFixtures.guarded()
                .callback("file:///data/git-repos/mirrors/1/2.git/")
                .configure(local);

        verify(local).setOptionUploadPack("git-upload-pack");
    }

    /** A Git configuration can turn redirects on, so each connection of a fetch must stay on the requested origin. */
    @Test
    void shouldRefuseAConnectionThatARedirectMovedToAnotherOrigin() throws Exception {
        var factory = new GuardedGitTransport.Factory(
                        mock(HttpConnectionFactory2.class), endpoints(), mock(X509ExtendedTrustManager.class))
                .forOrigin(new URIish("https://gitlab.example.com/team/repo.git"));

        for (var redirect : List.of(
                "https://collector.example.net/steal.git/info/refs",
                "https://gitlab.example.com:8443/team/repo.git/info/refs",
                "http://gitlab.example.com/team/repo.git/info/refs")) {
            assertThatThrownBy(() -> factory.create(URI.create(redirect).toURL()))
                    .as(redirect)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("left the requested origin");
        }
    }

    /** Only HTTPS reaches the egress check, so every other way to a host is refused before JGit connects. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://gitlab.example.com/team/repo.git",
                "git://gitlab.example.com/team/repo.git",
                "ssh://git@gitlab.example.com/team/repo.git",
                "git@gitlab.example.com:team/repo.git",
                "http://127.0.0.1:8080/team/repo.git",
                "file://evil.example/team/repo.git"
            })
    void shouldRefuseAFetchThatIsNotHttpsOrLocal(String cloneUrl) throws Exception {
        var transport = mock(Transport.class);
        when(transport.getURI()).thenReturn(new URIish(cloneUrl));

        assertThatThrownBy(
                        () -> GitTransportFixtures.guarded().callback(cloneUrl).configure(transport))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use HTTPS");
    }

    @Test
    void shouldAcceptALocalPathWithoutAScheme() throws Exception {
        var local = mock(Transport.class);
        when(local.getURI()).thenReturn(new URIish("/data/git-repos/mirrors/1/2.git"));

        GitTransportFixtures.guarded()
                .callback("/data/git-repos/mirrors/1/2.git")
                .configure(local);

        verify(local).setOptionUploadPack("git-upload-pack");
    }

    @Test
    void shouldAllowPlainHttpOnlyToTheSimulator() throws Exception {
        var environment = new MockEnvironment().withProperty("hephaestus.e2e.scm-origin", "http://127.0.0.1:8099");
        environment.setActiveProfiles("e2e");
        var transports = new GuardedGitTransport(
                new ScmServerEndpointPolicy(environment, WebClient.builder(), new ScmEgressProperties(List.of())));
        var simulator = mock(Transport.class);
        when(simulator.getURI()).thenReturn(new URIish("http://127.0.0.1:8099/team/repo.git"));
        var otherPort = mock(Transport.class);
        when(otherPort.getURI()).thenReturn(new URIish("http://127.0.0.1:8098/team/repo.git"));

        transports.callback("http://127.0.0.1:8099/team/repo.git").configure(simulator);

        verify(simulator).setOptionUploadPack("git-upload-pack");
        assertThatThrownBy(() -> transports
                        .callback("http://127.0.0.1:8098/team/repo.git")
                        .configure(otherPort))
                .isInstanceOf(IllegalStateException.class);
    }
}
