package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.agent.adapter.ConversationSandboxCopiesAdapter;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.mentor.spi.ConversationSandboxCopies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The real sandbox owner over the test database, without calls to a Kubernetes cluster. */
public final class SandboxOwnerFixtures {
    private SandboxOwnerFixtures() {}

    public static AgentSandboxes owner(ApplicationContext beans, AttemptTokenIssuer issuer) {
        return owner(beans, issuer, beans.getBean(AgentRuns.class));
    }

    public static AgentSandboxes owner(ApplicationContext beans, AttemptTokenIssuer issuer, AgentRuns runs) {
        return owner(beans, issuer, runs, Mockito.mock(SandboxClaims.class));
    }

    public static AgentSandboxes owner(
            ApplicationContext beans, AttemptTokenIssuer issuer, AgentRuns runs, SandboxClaims claims) {
        return new AgentSandboxes(
                beans.getBean(AgentSandboxRepository.class),
                runs,
                claims,
                issuer,
                beans.getBean(TransactionTemplate.class),
                "http://gateway:8081",
                host -> "10.43.0.12",
                new SimpleMeterRegistry());
    }

    public static ConversationSandboxCopies revocations(ApplicationContext beans, @Nullable AgentSandboxes owner) {
        return new ConversationSandboxCopiesAdapter(new ConversationSandboxRevocations(
                beans.getBean(AgentSandboxRepository.class), beans.getBean(PlatformTransactionManager.class), owner));
    }
}
