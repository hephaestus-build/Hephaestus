package de.tum.cit.aet.hephaestus.testconfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Live test that hits a real LLM endpoint. The {@code HEPHAESTUS_LIVE_LLM_API_KEY} environment
 * variable is the <em>primary gate</em> — JUnit skips the entire class/method when it is unset,
 * so the test never reaches the runtime credential check. Pair with {@link LiveDockerTest} when
 * the test also requires a Docker daemon.
 *
 * <p>Tagged {@code live}: excluded from every non-live tier. Run
 * {@code ./gradlew :application:liveTest} to opt in.
 *
 * <p>Pinned to {@link ExecutionMode#SAME_THREAD} so two live tests in the same module never run
 * in parallel — keeps LLM rate limits, log interleaving, and shared temp dirs sane.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("live")
@EnabledIfEnvironmentVariable(named = "HEPHAESTUS_LIVE_LLM_API_KEY", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
public @interface LiveLlmTest {}
