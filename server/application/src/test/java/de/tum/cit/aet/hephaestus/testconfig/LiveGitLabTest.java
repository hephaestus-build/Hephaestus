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
 * Live test that hits a real GitLab instance. Gated by {@code HEPHAESTUS_LIVE_GITLAB_TOKEN_FILE}.
 * The file holds the fixture setup credential; each connection uses a disposable non-admin token.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("live")
@EnabledIfEnvironmentVariable(named = "HEPHAESTUS_LIVE_GITLAB_TOKEN_FILE", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
public @interface LiveGitLabTest {}
