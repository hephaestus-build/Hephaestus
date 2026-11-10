package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.ConversationToolsPodRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.ConversationToolsPods;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextFolder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextGenerations;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextRevocations;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.ConversationLoopProperties;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conversation turns are a worker capability: every worker hands them to the conversation loop and serves their calls.
 * Artifact generation and the image's training run hand out no turn.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
@Profile(HephTurnConfiguration.PROFILES)
public class HephTurnConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HephTurnConfiguration.class);

    static final String PROFILES = "!specs & !cds-training";

    /**
     * Tools Pods that wait started, ahead of the turns that take them, for the whole installation. The chart's {@code
     * sandbox-pod-requests.json} sizes the conversations quota by it.
     */
    public static final int SANDBOX_POOL = 2;

    /** How often {@link ConversationToolsPods#reap} runs on each worker; its first run is at startup. */
    static final Duration SANDBOX_REAP = Duration.ofSeconds(60);

    @Bean
    TurnContextGenerations turnContextGenerations() {
        return new TurnContextGenerations();
    }

    /**
     * The installation's tools Pods. A change of what a workspace's turns may read, on any process, revokes that
     * workspace's tools Pods in its own transaction ({@code ConversationToolsPodRevocations}); every worker then makes a
     * new context generation and deletes their Jobs ({@link TurnContextRevocations}).
     */
    @Bean(destroyMethod = "close")
    ConversationToolsPods conversationToolsPods(
            ToolsPods pods,
            ConversationToolsPodRepository rows,
            PlatformTransactionManager transactions,
            MentorLedgerNotifications notifications,
            TurnContextGenerations generations,
            MeterRegistry meters) {
        ConversationToolsPods toolsPods =
                new ConversationToolsPods(pods, rows, new TransactionTemplate(transactions), SANDBOX_POOL, meters);
        notifications.subscribe(new TurnContextRevocations(generations, toolsPods));
        return toolsPods;
    }

    @Bean
    TurnContextFolder turnContextFolder(
            ConversationToolsPods toolsPods,
            MentorTurnRepository turns,
            ObjectProvider<ContextLoader> loaders,
            Clock clock) {
        return new TurnContextFolder(toolsPods, turns, loaders, clock);
    }

    /** The turns' cap is the claim's ({@code hephaestus.agent.capacity.turns}). */
    @Bean
    HephTurns hephTurns(
            MentorTurnStarter starter,
            MentorTurnService turns,
            MentorTurnRepository rows,
            MentorRedaction redaction,
            MentorTurnCompletion completion,
            MentorTurnProgress progress,
            MentorLedgerNotifications notifications,
            ProxyCallsInFlight callsInFlight,
            ConversationToolsPods toolsPods,
            AttemptTokenIssuer issuer,
            TurnRunsAdapter turnRuns,
            ConversationLoopProperties loop,
            Clock clock,
            MeterRegistry meters) {
        loop.requireFormat();
        if (!loop.configured()) {
            // The worker profile requires it; a local worker without it serves no conversation.
            log.warn("hephaestus.conversation-loop.secret is not set: this worker hands out no conversation turn");
        }
        return new HephTurns(
                starter,
                turns,
                rows,
                redaction,
                completion,
                progress,
                notifications,
                callsInFlight,
                toolsPods,
                issuer,
                turnRuns,
                clock,
                meters);
    }

    /** {@link ConversationToolsPods#reap} on a thread of its own: it lists and removes Pods, which can wait. */
    @Bean
    ScheduledTaskRegistrar conversationToolsPodReaps(ConversationToolsPods toolsPods) {
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(new FixedDelayTask(toolsPods::reap, SANDBOX_REAP, Duration.ZERO));
        return tasks;
    }
}
