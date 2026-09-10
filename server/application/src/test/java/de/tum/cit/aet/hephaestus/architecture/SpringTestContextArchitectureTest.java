package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.BeanOverride;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoBeans;

class SpringTestContextArchitectureTest extends HephaestusArchitectureTest {

    private static final Map<String, String> FULL_CONTEXT_ASSIGNMENTS = Map.ofEntries(
            assignment("testconfig.BaseIntegrationTest", "base"),
            assignment("agent.job.AgentJobControllerIntegrationTest", "execution-capture-enabled"),
            assignment("agent.job.DeferredIssueEventIntegrationTest", "issue-event-transaction"),
            assignment("agent.job.IssueUpdateCoalescerIntegrationTest", "issue-coalescer-transaction"),
            assignment("testconfig.RealAuthIntegrationTest", "real-auth"),
            assignment("StartupBudgetIntegrationTest", "startup"),
            assignment("core.auth.dev.DevLoginIntegrationTest", "dev-login"),
            assignment("integration.scm.github.BaseGitHubLiveIntegrationTest", "github-live"),
            assignment("integration.outline.OutlineFrameworkRegistrationIntegrationTest", "outline-enabled"),
            assignment(
                    "integration.outline.collection.OutlineCollectionAdminControllerIntegrationTest",
                    "outline-enabled"),
            assignment(
                    "integration.outline.connect.OutlineConnectionAdminControllerIntegrationTest", "outline-enabled"),
            assignment("integration.outline.sync.OutlineDocumentSyncIntegrationTest", "outline-enabled"),
            assignment("integration.core.sync.api.SyncControllerIntegrationTest", "sync-controller-focused"),
            assignment("integration.slack.detection.ConversationThreadDetectionIntegrationTest", "slack-ingest"),
            assignment("integration.slack.SlackConsentLifecycleE2EIntegrationTest", "slack-lifecycle"),
            assignment("integration.slack.channel.SlackChannelAdminControllerIntegrationTest", "slack-signed"));

    private static final Map<String, String> FULL_CONTEXT_JUSTIFICATIONS = Map.ofEntries(
            Map.entry("base", "shared PostgreSQL, HTTP, security, and application acceptance context"),
            Map.entry("execution-capture-enabled", "enabled private capture must not expose raw archives over HTTP"),
            Map.entry(
                    "issue-event-transaction",
                    "real transactional event listener with controlled workspace resolution"),
            Map.entry(
                    "issue-coalescer-transaction",
                    "real proxied job submission with controlled issue admission and bounded database lock waits"),
            Map.entry("real-auth", "real OAuth and authentication wiring without test security"),
            Map.entry("startup", "production main-method startup instrumentation"),
            Map.entry("dev-login", "dev-login feature-property behavior"),
            Map.entry("github-live", "explicitly selected live GitHub profile and credentials"),
            Map.entry("outline-enabled", "enabled Outline integration wiring"),
            Map.entry("slack-ingest", "enabled Slack ingest wiring with its review-submission spy"),
            Map.entry("slack-lifecycle", "agent submission boundary override"),
            Map.entry("slack-signed", "enabled signed Slack HTTP wiring"),
            Map.entry("sync-controller-focused", "controlled sync provider and runner behavior"));

    private static final Set<String> SPRING_BOOT_TESTS = names(
            "StartupBudgetIntegrationTest", "testconfig.BaseIntegrationTest", "testconfig.RealAuthIntegrationTest");

    private static final Set<String> JSON_TESTS = names(
            "integration.MultiVersionFixtureTest",
            "integration.outline.client.OutlineApiFixtureDeserializationTest",
            "integration.outline.client.OutlineDeserializationToleranceTest");

    private static final Set<String> DATA_JPA_TESTS =
            names("integration.schema.ProductionSchemaContractIntegrationTest");

    private static final Set<String> DYNAMIC_PROPERTY_TESTS = names(
            "StartupBudgetIntegrationTest",
            "integration.schema.ProductionSchemaContractIntegrationTest",
            "testconfig.BaseIntegrationTest",
            "testconfig.RealAuthIntegrationTest");

    private static final Set<String> PROPERTY_SOURCE_TESTS = names(
            "agent.job.AgentJobControllerIntegrationTest",
            "agent.job.IssueUpdateCoalescerIntegrationTest",
            "core.auth.dev.DevLoginIntegrationTest",
            "integration.outline.OutlineFrameworkRegistrationIntegrationTest",
            "integration.outline.collection.OutlineCollectionAdminControllerIntegrationTest",
            "integration.outline.connect.OutlineConnectionAdminControllerIntegrationTest",
            "integration.outline.sync.OutlineDocumentSyncIntegrationTest",
            "integration.slack.SlackConsentLifecycleE2EIntegrationTest",
            "integration.slack.channel.SlackChannelAdminControllerIntegrationTest",
            "integration.slack.detection.ConversationThreadDetectionIntegrationTest",
            "testconfig.BaseIntegrationTest");

    private static final Set<String> DIRTY_CONTEXT_TESTS = Set.of();

    private static final Set<String> MOCKITO_BEAN_TESTS =
            names("integration.slack.SlackConsentLifecycleE2EIntegrationTest");

    @Test
    void shouldMatchReviewedContextDeclarationsWhenArchitectureTestsRun() {
        assertInventory(SpringBootTest.class, SPRING_BOOT_TESTS);
        assertInventory(JsonTest.class, JSON_TESTS);
        assertInventory(DataJpaTest.class, DATA_JPA_TESTS);
        assertInventory(TestPropertySource.class, PROPERTY_SOURCE_TESTS);
        assertInventory(DirtiesContext.class, DIRTY_CONTEXT_TESTS);
        Set<String> mockitoBeans = classesWithTests.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(MockitoBean.class)
                        || javaClass.isAnnotatedWith(MockitoBeans.class)
                        || javaClass.getFields().stream()
                                .anyMatch(field -> field.isAnnotatedWith(MockitoBean.class)
                                        || field.isAnnotatedWith(MockitoBeans.class)))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(mockitoBeans)
                .as(
                        "@MockitoBean changes Spring's context-cache key; reuse an existing configuration or review this inventory")
                .isEqualTo(MOCKITO_BEAN_TESTS);

        Set<String> dynamicProperties = classesWithTests.stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(DynamicPropertySource.class))
                .map(method -> method.getOwner().getName())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(dynamicProperties)
                .as("@DynamicPropertySource changes Spring's context-cache key; reuse a reviewed test base")
                .isEqualTo(DYNAMIC_PROPERTY_TESTS);
    }

    @Test
    void shouldKeepTheReviewedFullApplicationContextBudgetWhenArchitectureTestsRun() {
        Set<JavaClass> fullContextTests = classesWithTests.stream()
                .filter(this::usesFullApplicationContext)
                .collect(Collectors.toSet());
        Set<String> configurationBearingTests = fullContextTests.stream()
                .filter(this::hasDirectContextCustomizer)
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(configurationBearingTests)
                .as("Every full-context configuration variant must be assigned to a reviewed merged key")
                .isEqualTo(FULL_CONTEXT_ASSIGNMENTS.keySet());

        Set<String> mergedKeys =
                fullContextTests.stream().map(this::mergedContextKey).collect(Collectors.toCollection(TreeSet::new));
        assertThat(mergedKeys).hasSizeLessThanOrEqualTo(15);
        assertThat(FULL_CONTEXT_JUSTIFICATIONS.keySet()).isEqualTo(mergedKeys);
        assertThat(FULL_CONTEXT_JUSTIFICATIONS.values())
                .allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    void shouldRejectLegacyMockBeansWhenArchitectureTestsRun() {
        Set<String> declarations = classesWithTests.stream()
                .filter(javaClass -> hasAnnotationNamed(javaClass.getAnnotations(), "MockBean", "MockBeans")
                        || javaClass.getFields().stream()
                                .anyMatch(field -> hasAnnotationNamed(field.getAnnotations(), "MockBean", "MockBeans")))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(declarations)
                .as("Use Spring Framework @MockitoBean; legacy @MockBean is forbidden")
                .isEmpty();
    }

    @Test
    void shouldDetectImplicitContextCustomizersWhenArchitectureTestsRun() {
        assertThat(hasDirectContextCustomizer(classesWithTests.get(NestedConfigurationFixture.class)))
                .isTrue();
        assertThat(hasDirectContextCustomizer(classesWithTests.get(TestBeanFixture.class)))
                .isTrue();
    }

    private boolean hasAnnotationNamed(Set<? extends JavaAnnotation<?>> annotations, String... names) {
        Set<String> expectedNames = Set.of(names);
        return annotations.stream()
                .anyMatch(annotation ->
                        expectedNames.contains(annotation.getRawType().getSimpleName()));
    }

    private void assertInventory(Class<? extends Annotation> annotation, Set<String> expected) {
        Set<String> actual = classesWithTests.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(annotation))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(actual)
                .as(
                        "@%s changes Spring's context-cache key; reuse an existing configuration or review this inventory",
                        annotation.getSimpleName())
                .isEqualTo(expected);
    }

    private boolean usesFullApplicationContext(JavaClass javaClass) {
        return (javaClass.isAnnotatedWith(SpringBootTest.class)
                || javaClass.getAllRawSuperclasses().stream()
                        .anyMatch(superclass -> superclass.getName().equals(name("testconfig.BaseIntegrationTest"))
                                || superclass.getName().equals(name("testconfig.RealAuthIntegrationTest"))));
    }

    private boolean hasDirectContextCustomizer(JavaClass javaClass) {
        Set<String> cacheKeyAnnotations = Set.of(
                "ActiveProfiles",
                "ContextConfiguration",
                "ContextHierarchy",
                "DirtiesContext",
                "Import",
                "MockitoBean",
                "MockitoBeans",
                "MockitoSpyBean",
                "MockitoSpyBeans",
                "SpringBootTest",
                "TestPropertySource",
                "WebAppConfiguration");
        return (javaClass.getAnnotations().stream()
                        .anyMatch(annotation -> cacheKeyAnnotations.contains(
                                annotation.getRawType().getSimpleName()))
                || javaClass.getFields().stream()
                        .anyMatch(field -> field.getAnnotations().stream().anyMatch(this::isBeanOverride))
                || javaClass.getMethods().stream()
                        .anyMatch(method -> method.isAnnotatedWith(DynamicPropertySource.class))
                || classesWithTests.stream()
                        .filter(candidate -> isNestedInside(candidate, javaClass))
                        .anyMatch(candidate -> candidate.isAnnotatedWith(TestConfiguration.class)));
    }

    private boolean isBeanOverride(JavaAnnotation<?> annotation) {
        return annotation.getRawType().isMetaAnnotatedWith(BeanOverride.class);
    }

    private boolean isNestedInside(JavaClass candidate, JavaClass owner) {
        JavaClass current = candidate;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().orElseThrow();
            if (current.equals(owner)) return true;
        }
        return false;
    }

    private String mergedContextKey(JavaClass javaClass) {
        String assigned = FULL_CONTEXT_ASSIGNMENTS.get(javaClass.getName());
        if (assigned != null) return assigned;
        boolean realAuth = javaClass.getAllRawSuperclasses().stream()
                .anyMatch(superclass -> superclass.getName().equals(name("testconfig.RealAuthIntegrationTest")));
        return realAuth ? "real-auth" : "base";
    }

    private static Set<String> names(String... relativeNames) {
        return java.util.Arrays.stream(relativeNames)
                .map(name -> "de.tum.cit.aet.hephaestus." + name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map.Entry<String, String> assignment(String relativeName, String key) {
        return Map.entry(name(relativeName), key);
    }

    private static String name(String relativeName) {
        return "de.tum.cit.aet.hephaestus." + relativeName;
    }

    private static final class NestedConfigurationFixture {

        @TestConfiguration
        static class Configuration {}
    }

    private static final class TestBeanFixture {

        @TestBean
        @Nullable
        String dependency;

        static String dependency() {
            return "fixture";
        }
    }
}
