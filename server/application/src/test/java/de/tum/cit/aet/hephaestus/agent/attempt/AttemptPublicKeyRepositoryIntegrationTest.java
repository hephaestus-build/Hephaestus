package de.tum.cit.aet.hephaestus.agent.attempt;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AttemptPublicKeyRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AttemptPublicKeyRepository keys;

    @Test
    void shouldListAPublishedKeyUntilItExpires() {
        String live = UUID.randomUUID().toString();
        String expired = UUID.randomUUID().toString();

        keys.publish(live, new byte[] {1}, 3600);
        keys.publish(expired, new byte[] {2}, -1);

        assertThat(keys.findLive())
                .extracting(AttemptPublicKey::getKid)
                .contains(live)
                .doesNotContain(expired);
    }

    /** A publication of a live worker moves its expiry and leaves its key alone. */
    @Test
    void shouldExtendAKeyThatIsPublishedAgain() {
        String kid = UUID.randomUUID().toString();
        keys.publish(kid, new byte[] {1}, -1);

        keys.publish(kid, new byte[] {9}, 3600);

        assertThat(keys.findLive())
                .filteredOn(key -> key.getKid().equals(kid))
                .singleElement()
                .extracting(AttemptPublicKey::getPublicKey)
                .isEqualTo(new byte[] {1});
    }

    @Test
    void shouldDeleteOnlyExpiredKeys() {
        String live = UUID.randomUUID().toString();
        String expired = UUID.randomUUID().toString();
        keys.publish(live, new byte[] {1}, 3600);
        keys.publish(expired, new byte[] {2}, -1);

        keys.deleteExpired();

        assertThat(keys.existsById(live)).isTrue();
        assertThat(keys.existsById(expired)).isFalse();
    }
}
