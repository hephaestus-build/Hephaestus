package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Releases fixture databases at the end of each test invocation. */
public final class TestDatabaseCleanup implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TestDatabaseCleanup.class);

    private static final ThreadLocal<ExtensionContext.Store> TEST_STORE = new ThreadLocal<>();

    @Override
    public void beforeEach(ExtensionContext context) {
        TEST_STORE.set(context.getStore(NAMESPACE));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TEST_STORE.remove();
    }

    /** Creates an empty fixture database for the current test. */
    public static TestDatabase createDatabase(String name) {
        return releaseAfterTest(name, () -> PostgreSQLTestContainer.createDatabase(name));
    }

    /** Copies a fixture template for the current test. */
    public static TestDatabase copyDatabase(String name, TestDatabase template) {
        return releaseAfterTest(name, () -> PostgreSQLTestContainer.copyDatabase(name, template));
    }

    private static TestDatabase releaseAfterTest(String name, Supplier<TestDatabase> create) {
        var store = Objects.requireNonNull(TEST_STORE.get(), "A fixture database requires an active test");
        var database = create.get();
        store.put(name, (AutoCloseable) () -> PostgreSQLTestContainer.dropDatabase(name));
        return database;
    }
}
