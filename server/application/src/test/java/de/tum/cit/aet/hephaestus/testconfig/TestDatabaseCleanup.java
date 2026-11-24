package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.util.Objects;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Releases copies of practice fixtures at the end of each test invocation. */
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

    /** Copies a fixture template and lets JUnit drop the copy when it closes the test's store. */
    public static TestDatabase copyDatabase(String name, TestDatabase template) {
        var store = Objects.requireNonNull(TEST_STORE.get(), "A fixture copy requires an active test");
        var database = PostgreSQLTestContainer.copyDatabase(name, template);
        store.put(name, (AutoCloseable) () -> PostgreSQLTestContainer.dropDatabase(name));
        return database;
    }
}
