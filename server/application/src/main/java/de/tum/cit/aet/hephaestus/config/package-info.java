/**
 * Cross-cutting Spring {@code @Configuration} classes — shared kernel.
 *
 * <p>Holds {@code @Configuration} beans that wire integrations needed by multiple
 * modules (Slack, provider inbox connection, GraphQL clients, Resilience4j,
 * Jackson formats, JWT decoders). Marked {@code Type.OPEN} so any module can depend
 * on the beans here without Modulith violation.
 *
 * <p>If a {@code @Configuration} class is single-module-scoped, prefer placing it
 * next to the module instead of here.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Config (shared kernel)",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.config;
