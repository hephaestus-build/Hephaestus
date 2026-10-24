package de.tum.cit.aet.hephaestus.testconfig;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test class that tried to resolve a real host, and forgets the names it declared. JUnit registers it for
 * every class through {@code META-INF/services}; a fire-and-forget call whose error nobody reads still fails its
 * class. Attempts are counted JVM-wide: test classes run one at a time, and background work of a cached context is
 * charged to the class that runs while it happens.
 */
public final class OfflineNetworkExtension implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(OfflineNetworkExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getStore(NAMESPACE).put("seen", OfflineNetwork.attempts().size());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        // A nested class shares the names its enclosing class declared.
        if (context.getParent().flatMap(ExtensionContext::getTestClass).isEmpty()) OfflineNetwork.forgetAnswers();
        Integer seen = context.getStore(NAMESPACE).get("seen", Integer.class);
        var attempts = OfflineNetwork.attempts();
        var made = attempts.subList(seen == null ? 0 : seen, attempts.size());
        if (!made.isEmpty()) {
            throw new AssertionError(context.getDisplayName()
                    + " tried to reach real hosts " + made.stream().distinct().toList()
                    + "; tests make no external network calls (docs/contributor/testing.mdx)");
        }
    }
}
