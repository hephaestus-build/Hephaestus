package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tests a stored connection and stores the token limits that its provider reports for the connection's models
 * ({@link LlmConnectionProbeService#probeWithLimits}). Its update sets only the discovered columns, so it never overwrites
 * an admin's value, not even one saved while the probe ran; the admin's value wins when the limits resolve ({@link
 * ModelLimits}). A model that the provider still lists gets what it reports now, absent limits included; a model it no
 * longer lists keeps its last discovered values.
 *
 * <p>Deliberately NOT {@code @Transactional}: the probe's network calls run with no JDBC connection held, or admins
 * probing a stalled provider pin the pool. Only the write takes a transaction, and it stores nothing when the connection
 * changed while the probe ran.
 */
@Service
@WorkspaceAgnostic("Each workspace method scopes every read and write by the workspace it receives")
public class LlmModelLimitDiscovery {

    private static final Logger log = LoggerFactory.getLogger(LlmModelLimitDiscovery.class);

    private final LlmConnectionProbeService probeService;
    private final LlmConnectionRepository connectionRepository;
    private final LlmModelRepository modelRepository;
    private final WorkspaceLlmConnectionRepository workspaceConnectionRepository;
    private final WorkspaceLlmModelRepository workspaceModelRepository;
    private final EgressPolicy egressPolicy;
    private final TransactionTemplate transactions;

    LlmModelLimitDiscovery(
            LlmConnectionProbeService probeService,
            LlmConnectionRepository connectionRepository,
            LlmModelRepository modelRepository,
            WorkspaceLlmConnectionRepository workspaceConnectionRepository,
            WorkspaceLlmModelRepository workspaceModelRepository,
            EgressPolicy egressPolicy,
            TransactionTemplate transactions) {
        this.probeService = probeService;
        this.connectionRepository = connectionRepository;
        this.modelRepository = modelRepository;
        this.workspaceConnectionRepository = workspaceConnectionRepository;
        this.workspaceModelRepository = workspaceModelRepository;
        this.egressPolicy = egressPolicy;
        this.transactions = transactions;
    }

    /** Tests an instance connection and stores the limits its provider reports for the connection's models. */
    public LlmProbeResultDTO probeInstanceConnection(long connectionId) {
        LlmProbeTarget target = connectionRepository
                .findProbeTargetById(connectionId)
                .orElseThrow(() -> new EntityNotFoundException("LlmConnection", connectionId));
        boolean privateTarget = egressPolicy.validate(target.baseUrl());
        List<String> upstreamIds = modelRepository.findUpstreamModelIdsByConnectionId(connectionId);
        var probe = probeService.probeWithLimits(target, privateTarget, upstreamIds);
        if (!probe.limits().isEmpty())
            transactions.executeWithoutResult(tx -> {
                if (!connectionRepository
                        .findProbeTargetById(connectionId)
                        .map(target::equals)
                        .orElse(false)) return;
                probe.limits()
                        .forEach((upstreamId, found) -> modelRepository.recordDiscoveredLimits(
                                connectionId, upstreamId, found.contextWindow(), found.maxOutputTokens()));
            });
        log.info(
                "Tested LLM connection: scope=INSTANCE, connectionId={}, reachable={}, modelsWithLimits={}",
                connectionId,
                probe.result().reachable(),
                probe.limits().size());
        return probe.result();
    }

    /** Tests a workspace's own connection and stores the limits its provider reports for the connection's models. */
    public LlmProbeResultDTO probeWorkspaceConnection(long workspaceId, long connectionId) {
        LlmProbeTarget target = workspaceConnectionRepository
                .findProbeTargetByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceLlmConnection", connectionId));
        boolean privateTarget = egressPolicy.validate(target.baseUrl());
        List<String> upstreamIds =
                workspaceModelRepository.findUpstreamModelIdsByConnectionIdAndWorkspaceId(connectionId, workspaceId);
        var probe = probeService.probeWithLimits(target, privateTarget, upstreamIds);
        if (!probe.limits().isEmpty())
            transactions.executeWithoutResult(tx -> {
                if (!workspaceConnectionRepository
                        .findProbeTargetByIdAndWorkspaceId(connectionId, workspaceId)
                        .map(target::equals)
                        .orElse(false)) return;
                probe.limits()
                        .forEach((upstreamId, found) -> workspaceModelRepository.recordDiscoveredLimits(
                                workspaceId, connectionId, upstreamId, found.contextWindow(), found.maxOutputTokens()));
            });
        log.info(
                "Tested LLM connection: scope=WORKSPACE, workspaceId={}, connectionId={}, reachable={}, modelsWithLimits={}",
                workspaceId,
                connectionId,
                probe.result().reachable(),
                probe.limits().size());
        return probe.result();
    }
}
