package de.tum.cit.aet.hephaestus.agent.attempt;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keyrings for tests that need tokens without a database. */
public final class AttemptKeyFixtures {

    private AttemptKeyFixtures() {}

    public static AttemptKeyring keyring(AttemptSigningKey key) {
        return new AttemptKeyring(key, new InMemoryStore());
    }

    /** Keeps keys in memory; two keyrings on one store behave as two workers on one database. */
    public static final class InMemoryStore implements AttemptKeyStore {
        final Map<String, byte[]> keys = new ConcurrentHashMap<>();
        int reads;
        boolean failing;

        @Override
        public void publish(String kid, byte[] publicKey, Duration keptFor) {
            keys.put(kid, publicKey);
        }

        @Override
        public Map<String, byte[]> live() {
            reads++;
            if (failing) throw new IllegalStateException("store unreachable");
            return Map.copyOf(keys);
        }

        @Override
        public void removeExpired() {}
    }
}
