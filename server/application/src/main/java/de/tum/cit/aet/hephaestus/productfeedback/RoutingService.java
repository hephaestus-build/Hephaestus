package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Every trust decision about a report, in the server role. Before drafting it redacts the person's text, so the model
 * provider never sees what redaction finds. After drafting it validates the answer, redacts the draft again, lets
 * {@link RoutingGate} decide, and freezes the recipient repository before any hold starts.
 */
@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class RoutingService {
    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final DestinationSettings destinations;
    private final KnownIdentities identities;
    private final ProductFeedbackIssues issues;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final ProductFeedbackMeters meters;
    private final String appVersion;

    RoutingService(
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            DestinationSettings destinations,
            KnownIdentities identities,
            ProductFeedbackIssues issues,
            ObjectMapper mapper,
            Clock clock,
            TransactionTemplate transactions,
            ProductFeedbackMeters meters,
            @Value("${spring.application.version}") String appVersion) {
        this.filings = filings;
        this.feedback = feedback;
        this.destinations = destinations;
        this.identities = identities;
        this.issues = issues;
        this.mapper = mapper;
        this.clock = clock;
        this.transactions = transactions;
        this.meters = meters;
        this.appVersion = appVersion;
    }

    /** Sets the text the model drafts from. A blocking finding in it or in the page route sets the risk latch for good. */
    void prepare(ProductFeedbackFiling filing, ProductFeedback report, String text) {
        var known = identities.of(report.getAccountId());
        var redacted = Redaction.redact(text, known, linkRepository(destinations.current()));
        String path = report.getPagePath();
        var route = path == null ? null : Redaction.redact(path, known, "");
        if (redacted.blocking() || (route != null && route.blocking())) filing.setRiskLatch(true);
        filing.setPromptInput(redacted.text());
        filing.setPageRoute(route == null || filing.isRiskLatch() ? null : route.text());
    }

    @Scheduled(fixedDelayString = "PT2S", initialDelayString = "PT15S")
    void routeAnswered() {
        for (int i = 0; i < 20; i++) {
            try {
                Lookup intent = transactions.execute(status -> claim());
                if (intent == null) return;
                String target = intent.target();
                var repository = target == null ? null : freeze(target, intent.route());
                transactions.executeWithoutResult(status -> complete(intent, repository));
            } catch (RuntimeException failed) {
                meters.failed(failed);
                log.warn(
                        "Product feedback routing failed: error={}",
                        failed.getClass().getSimpleName());
                return;
            }
        }
    }

    private record Lookup(
            UUID id,
            int generation,
            @Nullable String target,
            FilingRoute route,
            @Nullable Draft draft,
            Instant destinationUpdatedAt) {}

    private @Nullable Lookup claim() {
        Instant now = clock.instant();
        UUID id = filings.claimableAnswer(now).orElse(null);
        if (id == null) return null;
        ProductFeedbackFiling filing = filings.lock(id).orElseThrow();
        ProductFeedback report = feedback.findById(id).orElseThrow();
        ProductFeedbackDestination destination = destinations.current();
        Draft draft = null;
        String answer = filing.getAnswer();
        if (answer != null) {
            var open = new HashSet<Long>();
            Snapshot.read(destination.getSnapshot()).forEach(entry -> open.add(entry.number()));
            try {
                draft = Draft.parse(answer, mapper, open);
                meters.draft("ok");
            } catch (Draft.Invalid invalid) {
                meters.draft("invalid");
            }
        }
        var outcome = evaluate(filing, report, destination, draft);
        filing.setAttempt(filing.getAttempt() + 1);
        filing.setNextAttemptAt(now.plus(ProductFeedbackFiling.LOOKUP_LEASE));
        filing.setUpdatedAt(now);
        return new Lookup(
                id,
                filing.getAttempt(),
                target(outcome.decision().route(), destination),
                outcome.decision().route(),
                draft,
                destination.getUpdatedAt());
    }

    private void complete(Lookup intent, ProductFeedbackIssues.@Nullable Repository repository) {
        var filing = filings.lock(intent.id())
                .filter(row -> row.getState() == FilingState.ANSWERED && row.getAttempt() == intent.generation())
                .orElse(null);
        if (filing == null) return;
        Instant now = clock.instant();
        filing.setNextAttemptAt(now);
        var report = feedback.findById(intent.id()).orElse(null);
        if (report == null) return;
        var destination = destinations.current();
        var outcome = evaluate(filing, report, destination, intent.draft());
        if (!destination.getUpdatedAt().equals(intent.destinationUpdatedAt())
                || outcome.decision().route() != intent.route()
                || !Objects.equals(target(outcome.decision().route(), destination), intent.target())) return;
        route(filing, report, intent.draft(), outcome, repository, now);
    }

    private RoutingGate.Outcome evaluate(
            ProductFeedbackFiling filing,
            ProductFeedback report,
            ProductFeedbackDestination destination,
            @Nullable Draft draft) {
        return RoutingGate.evaluate(
                filing.isAllowPublic(),
                destination.getPublicRepository() != null,
                destination.getPrivateRepository() != null,
                report.getMessage(),
                identities.of(report.getAccountId()),
                linkRepository(destination),
                draft,
                filing.isRiskLatch());
    }

    private static @Nullable String target(FilingRoute route, ProductFeedbackDestination destination) {
        return switch (route) {
            case PUBLIC -> destination.getPublicRepository();
            case PRIVATE -> destination.getPrivateRepository();
            case ADMINS -> null;
        };
    }

    /** The whole routing decision for one revision, stored on the row. Without a draft a report never goes public. */
    private void route(
            ProductFeedbackFiling filing,
            ProductFeedback report,
            @Nullable Draft draft,
            RoutingGate.Outcome outcome,
            ProductFeedbackIssues.@Nullable Repository repository,
            Instant now) {
        var decision = outcome.decision();
        if (decision.latch()) filing.setRiskLatch(true);
        filing.setRoute(decision.route());
        filing.setReason(decision.reason());
        // The prepared text holds the reporter's latest edit; the stored message holds only the first version.
        String input = filing.getPromptInput();
        Draft stored = outcome.draft() != null
                ? outcome.draft()
                : Fallback.of(
                        report.getKind(),
                        input != null ? input : outcome.original().text());
        filing.setDraft(write(stored));
        filing.setRemoved(outcome.counts().isEmpty() ? null : Redaction.describe(outcome.counts()));
        if (draft == null) filing.setDraftedAt(null);
        filing.setAnswer(null);
        filing.setNextAttemptAt(now);
        filing.setUpdatedAt(now);
        meters.route(decision.route(), decision.reason());
        if (decision.route() == FilingRoute.ADMINS) {
            filing.apply(FilingLifecycle.Event.ROUTED_TO_FILE);
            return;
        }
        if (repository == null) {
            filing.apply(FilingLifecycle.Event.ROUTED_TO_FILE);
            filing.setRoute(FilingRoute.ADMINS);
            filing.setReason(FilingReason.DESTINATION_UNAVAILABLE);
            return;
        }
        filing.setRepository(repository.fullName());
        filing.setRepositoryId(repository.id());
        filing.setRepositoryNodeId(repository.nodeId());
        filing.setInstallationId(repository.installationId());
        if (decision.route() == FilingRoute.PUBLIC) {
            filing.apply(FilingLifecycle.Event.ROUTED_TO_HOLD);
            if (filing.getFiledAfter() == null) filing.setFiledAfter(now.plus(DraftingService.HOLD));
        } else {
            filing.apply(FilingLifecycle.Event.ROUTED_TO_FILE);
            filing.setFiledAfter(null);
        }
    }

    /** The recipient as GitHub reports it now; a private destination that is public is refused. */
    private ProductFeedbackIssues.@Nullable Repository freeze(String name, FilingRoute route) {
        try {
            var found = issues.repository(name).orElse(null);
            if (found == null || (route == FilingRoute.PRIVATE && !found.isPrivate())) return null;
            return found;
        } catch (RuntimeException failed) {
            log.warn(
                    "Product feedback destination lookup failed: error={}",
                    failed.getClass().getSimpleName());
            return null;
        }
    }

    String write(Draft draft) {
        return mapper.writeValueAsString(draft);
    }

    @Nullable
    Draft read(@Nullable String json) {
        return json == null ? null : mapper.readValue(json, Draft.class);
    }

    String version() {
        return appVersion;
    }

    static String linkRepository(ProductFeedbackDestination destination) {
        String publicRepository = destination.getPublicRepository();
        if (publicRepository != null) return publicRepository;
        String privateRepository = destination.getPrivateRepository();
        return privateRepository != null ? privateRepository : "";
    }
}
