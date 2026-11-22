package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebClients;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * Every context built on {@code BaseIntegrationTest} reaches the GitLab at {@link ScriptedGitLabNotes#SERVER} through
 * {@link ScriptedGitLabNotes}: its REST calls and GraphQL documents answer from there, so the application's own
 * writers, readers and action adapters act on a GitLab the test reads. Every other server keeps the real transport.
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("!live")
public class ScriptedGitLabTransport {

    private static final String HOST = URI.create(ScriptedGitLabNotes.SERVER).getHost();

    @Bean
    ScriptedGitLabNotes scriptedGitLab() {
        // The guard serves only the fake's own summary channel, which this transport never uses.
        return new ScriptedGitLabNotes(mock(OutboundEgressGuard.class));
    }

    @Bean
    static BeanPostProcessor scriptedGitLabExchange(ObjectProvider<ScriptedGitLabNotes> gitlab) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof GitLabWebClients clients)
                    return (GitLabWebClients) () -> clients.builder()
                            .filter((request, next) -> HOST.equals(request.url().getHost())
                                    ? gitlab.getObject().exchange(request)
                                    : next.exchange(request));
                // Every GraphQL client the provider builds for a scope names that scope's server last.
                if (bean instanceof GitLabGraphQlClientProvider provider)
                    return mock(
                            GitLabGraphQlClientProvider.class,
                            withSettings()
                                    .spiedInstance(provider)
                                    .defaultAnswer(call -> call.getMethod()
                                                            .getName()
                                                            .equals("forScope")
                                                    && call.getArguments().length == 3
                                                    && ScriptedGitLabNotes.SERVER.equals(call.getArgument(2))
                                            ? gitlab.getObject().provider().forScope(call.<Long>getArgument(0))
                                            : call.callRealMethod()));
                return bean;
            }
        };
    }
}
