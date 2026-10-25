package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class OfflineNetworkTest extends BaseUnitTest {

    @Test
    void theLocalHostsCanonicalNameResolvesWithoutAnAttempt() throws UnknownHostException {
        int attempts = OfflineNetwork.attempts().size();
        InetAddress local = InetAddress.getLocalHost();

        String canonical = local.getCanonicalHostName();

        assertThat(InetAddress.getAllByName(canonical)).contains(local);
        assertThat(OfflineNetwork.attempts()).hasSize(attempts);
    }
}
