package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

// The JDK caches failed lookups, so each test resolves a name that no other class resolves.
class OfflineNetworkTest extends BaseUnitTest {

    @Test
    void shouldResolveWithoutAnAttemptWhenTheNameIsTheLocalHostsCanonicalName() throws UnknownHostException {
        int attempts = OfflineNetwork.attempts().size();
        InetAddress local = InetAddress.getLocalHost();

        String canonical = local.getCanonicalHostName();

        assertThat(InetAddress.getAllByName(canonical)).contains(local);
        assertThat(OfflineNetwork.attempts()).hasSize(attempts);
    }

    @Test
    void shouldRefuseAndRecordWhenTheHostIsReal() {
        int attempts = OfflineNetwork.attempts().size();

        assertThatThrownBy(() -> InetAddress.getAllByName("refused.offline-network-test.dev"))
                .isInstanceOf(UnknownHostException.class);

        assertThat(OfflineNetwork.attempts()
                        .subList(attempts, OfflineNetwork.attempts().size()))
                .containsExactly("refused.offline-network-test.dev");
        OfflineNetwork.forgetAttemptsAfter(attempts);
    }

    @Test
    void shouldFailWithoutAnAttemptWhenTheNameIsReserved() {
        int attempts = OfflineNetwork.attempts().size();

        assertThatThrownBy(() -> InetAddress.getAllByName("reserved.offline-network-test.example"))
                .isInstanceOf(UnknownHostException.class);

        assertThat(OfflineNetwork.attempts()).hasSize(attempts);
    }

    @Test
    void shouldResolveToTheDeclaredAddressWhenATestDeclaredTheName() throws UnknownHostException {
        int attempts = OfflineNetwork.attempts().size();
        OfflineNetwork.answer("declared.offline-network-test.dev", OfflineNetwork.PUBLIC_ADDRESS);

        InetAddress[] declared = InetAddress.getAllByName("declared.offline-network-test.dev");

        assertThat(declared).extracting(InetAddress::getHostAddress).containsExactly(OfflineNetwork.PUBLIC_ADDRESS);
        assertThat(OfflineNetwork.attempts()).hasSize(attempts);
    }
}
