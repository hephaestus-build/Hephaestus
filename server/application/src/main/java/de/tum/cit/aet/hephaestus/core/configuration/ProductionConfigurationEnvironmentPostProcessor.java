package de.tum.cit.aet.hephaestus.core.configuration;

import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

public final class ProductionConfigurationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationEnvironmentPostProcessor.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;
        List<ConfigurationFactDTO> failures = new ConfigurationReadinessEvaluator(environment)
                .evaluateDeployment().stream()
                        .filter(fact -> fact.requirement() == ConfigurationRequirement.REQUIRED)
                        .filter(fact -> fact.status() == ConfigurationStatus.ACTION_REQUIRED)
                        .toList();
        if (failures.isEmpty()) return;
        String diagnosis = failures.stream()
                .map(fact -> " - [" + fact.id() + "] " + fact.explanation() + " Set " + fact.subject() + ". "
                        + fact.documentationUrl())
                .collect(Collectors.joining("\n"));
        log.error("Production configuration validation failed with " + failures.size() + " problem(s):\n" + diagnosis);
        throw new IllegalStateException("Production configuration validation failed. Diagnostic identifiers: "
                + failures.stream().map(ConfigurationFactDTO::id).collect(Collectors.joining(", ")));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
