package de.tum.cit.aet.hephaestus.core;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnServerRole
@Profile("!prod")
@ConditionalOnProperty(name = ConditionalOnEvaluation.ENABLED, havingValue = "true")
public @interface ConditionalOnEvaluation {
    String ENABLED = "hephaestus.evaluation.controlled-clock.enabled";
}
