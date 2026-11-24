package de.tum.cit.aet.hephaestus.core.evaluation;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnEvaluation
@EnableConfigurationProperties(EvaluationProperties.class)
class EvaluationConfiguration {

    private static final String FAKE_CREDENTIAL = "hephaestus-evaluation";
    private static final List<String> PROVIDER_SECRETS = List.of(
            "hephaestus.contributors.github.auth-token",
            "hephaestus.integration.github.app.client-secret",
            "hephaestus.integration.slack.client-secret",
            "hephaestus.integration.slack.signing-secret",
            "hephaestus.workspace.default.token",
            "hephaestus.workspace.gitlab-default.token",
            "spring.mail.password");

    EvaluationConfiguration(Environment environment, AuthProperties auth) {
        Binder binder = Binder.get(environment);
        if (!binder.bind("hephaestus.integration.github.app.private-key", String.class)
                        .orElse("")
                        .isBlank()
                || !binder.bind("hephaestus.integration.github.app.private-key-location", String.class)
                        .orElse("")
                        .isBlank()) {
            throw new IllegalStateException("Controlled clock cannot run with a GitHub App private key");
        }
        for (String key : PROVIDER_SECRETS) {
            if (isCredential(binder.bind(key, String.class).orElse(""))) {
                throw new IllegalStateException("Controlled clock cannot run with a provider credential: " + key);
            }
        }
        if (auth.loginProviders().values().stream().anyMatch(provider -> isCredential(provider.clientSecret()))) {
            throw new IllegalStateException("Controlled clock cannot run with an OAuth client secret");
        }
    }

    private static boolean isCredential(String value) {
        return !value.isBlank() && !value.equals(FAKE_CREDENTIAL);
    }

    @Bean
    SmartInitializingSingleton evaluationLoginCredentialCheck(JdbcClient jdbc) {
        return () -> {
            if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM login_provider)")
                    .query(Boolean.class)
                    .single()) {
                throw new IllegalStateException("Controlled clock cannot run with stored login credentials");
            }
        };
    }

    @Bean
    EvaluationWork evaluationPublications(JdbcClient jdbc) {
        // The registry records the follow-up with its source transaction and deletes it after the listener ends.
        return workspaceIds -> jdbc.sql("SELECT id FROM event_publication WHERE completion_date IS NULL")
                .query((rs, row) ->
                        new EvaluationWork.Item("event_publication", rs.getString("id"), "INCOMPLETE", null))
                .list();
    }
}
