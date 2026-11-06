package de.tum.cit.aet.hephaestus.agent.attempt;

import java.time.Duration;
import java.util.Map;

/** Where workers publish the public halves of their signing keys. */
public interface AttemptKeyStore {

    /** Publishes a key, or extends a key that is published already. */
    void publish(String kid, byte[] publicKey, Duration keptFor);

    /** @return the public keys by {@code kid}, without the expired ones */
    Map<String, byte[]> live();

    void removeExpired();
}
