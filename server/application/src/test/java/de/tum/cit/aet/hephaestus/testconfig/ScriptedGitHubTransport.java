package de.tum.cit.aet.hephaestus.testconfig;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Every context built on {@code BaseIntegrationTest} reaches GitHub's GraphQL endpoint through {@link ScriptedGitHub}, so the application's
 * own senders, schedulers and listeners write to a provider the test reads. Live tests keep the real network.
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("!live")
public class ScriptedGitHubTransport {

    private static final String GRAPHQL_WEB_CLIENT = "gitHubGraphQlWebClient";

    @Bean
    ScriptedGitHub scriptedGitHub() {
        return new ScriptedGitHub();
    }

    @Bean
    static BeanPostProcessor scriptedGitHubExchange(ObjectProvider<ScriptedGitHub> github) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!name.equals(GRAPHQL_WEB_CLIENT) || !(bean instanceof WebClient client)) return bean;
                return client.mutate().exchangeFunction(github.getObject()).build();
            }
        };
    }
}
