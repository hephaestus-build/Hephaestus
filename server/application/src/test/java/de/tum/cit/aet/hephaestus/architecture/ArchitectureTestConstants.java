package de.tum.cit.aet.hephaestus.architecture;

/**
 * Centralized constants for all architecture tests.
 *
 * <p>All thresholds and package patterns are defined here to ensure consistency
 * across all architecture test files and prevent drift between tests.
 *
 * <p><b>Thresholds are based on industry best practices (2025):</b>
 * <ul>
 *   <li>Class methods: 25 max (SRP indicator)</li>
 *   <li>Constructor parameters: 12 max for every Spring bean, 5 max for controllers</li>
 *   <li>Interface methods: 8 max (ISP compliance)</li>
 * </ul>
 */
public final class ArchitectureTestConstants {

    private ArchitectureTestConstants() {
        // Prevent instantiation
    }

    // PACKAGE PATTERNS

    /** Base package for all application code. */
    public static final String BASE_PACKAGE = "de.tum.cit.aet.hephaestus";

    /** Generated code packages to exclude from most checks. */
    public static final String GENERATED_GRAPHQL_PACKAGE = "..graphql..model..";

    // COMPLEXITY THRESHOLDS (Single Responsibility Principle)

    /**
     * Maximum business methods per Spring bean.
     *
     * <p>Excludes getters, setters, equals, hashCode, toString, and constructors.
     * Classes exceeding this should be split into focused beans.
     */
    public static final int MAX_SERVICE_METHODS = 25;

    /**
     * Maximum constructor parameters for every Spring bean.
     *
     * <p>High dependency count indicates the class is doing too much.
     * Consider extracting responsibilities into new services.
     */
    public static final int MAX_SERVICE_DEPENDENCIES = 12;

    /**
     * Maximum constructor parameters for controller classes.
     *
     * <p>Controllers should be thin orchestrators that delegate to services.
     * They should rarely need more than a few service dependencies.
     */
    public static final int MAX_CONTROLLER_DEPENDENCIES = 5;

    // INTERFACE THRESHOLDS (Interface Segregation Principle)

    /**
     * Maximum methods per interface.
     *
     * <p>Large interfaces violate ISP. Consider splitting into smaller,
     * more focused interfaces.
     */
    public static final int MAX_INTERFACE_METHODS = 8;

    /**
     * Maximum methods per SPI (Service Provider Interface).
     *
     * <p>SPIs are implemented by external adapters and should be minimal.
     * Event listener SPIs may have more methods for lifecycle events.
     */
    public static final int MAX_SPI_METHODS = 8;

    // SPRING ANNOTATIONS

    /** Spring @Service annotation fully qualified name. */
    public static final String SPRING_SERVICE = "org.springframework.stereotype.Service";

    /** Spring @RestController annotation fully qualified name. */
    public static final String SPRING_REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";

    /** Spring @Transactional annotation fully qualified name. */
    public static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

    /** Spring @Autowired annotation fully qualified name. */
    public static final String SPRING_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";

    /** Spring @Configuration annotation fully qualified name. */
    public static final String SPRING_CONFIGURATION = "org.springframework.context.annotation.Configuration";

    /** Spring @Repository annotation fully qualified name. */
    public static final String SPRING_REPOSITORY = "org.springframework.stereotype.Repository";

    // SECURITY ANNOTATIONS

    /** Spring Security @PreAuthorize fully qualified name. */
    public static final String SPRING_PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";

    // TEST NAMING PATTERNS

    /** Pattern for unit test class names. */
    public static final String UNIT_TEST_SUFFIX = "Test";

    /** Pattern for integration test class names. */
    public static final String INTEGRATION_TEST_SUFFIX = "IntegrationTest";

    /** Pattern for live/E2E test class names. */
    public static final String LIVE_TEST_SUFFIX = "LiveTest";
}
