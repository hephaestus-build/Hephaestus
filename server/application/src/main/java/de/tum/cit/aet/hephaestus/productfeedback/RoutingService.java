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
                if (!Boolean.TRUE.equals(transactions.execute(status -> routeOne()))) return;
            } catch (RuntimeException failed) {
                log.warn(
                        "Product feedback routing failed: error={}",
                        failed.getClass().getSimpleName());
                return;
            }
        }
    }

    private boolean routeOne() {
        UUID id = filings.claimableAnswer().orElse(null);
        if (id == null) return false;
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
        route(filing, report, destination, draft, clock.instant());
        return true;
    }

    /** The whole routing decision for one revision, stored on the row. Without a draft a report never goes public. */
    void route(
            ProductFeedbackFiling filing,
            ProductFeedback report,
            ProductFeedbackDestination destination,
            @Nullable Draft draft,
            Instant now) {
        var outcome = RoutingGate.evaluate(
                filing.isAllowPublic(),
                report.getKind(),
                destination.getPublicRepository() != null,
                destination.getPrivateRepository() != null,
                report.getMessage(),
                identities.of(report.getAccountId()),
                linkRepository(destination),
                draft,
                filing.isRiskLatch());
        var decision = outcome.decision();
        if (decision.latch()) filing.setRiskLatch(true);
        filing.setRoute(decision.route());
        filing.setReason(decision.reason());
        Draft stored = outcome.draft() != null
                ? outcome.draft()
                : Fallback.of(report.getKind(), outcome.original().text());
        filing.setDraft(write(stored));
        filing.setRemoved(outcome.counts().isEmpty() ? null : Redaction.describe(outcome.counts()));
        if (draft == null) filing.setDraftedAt(null);
        filing.setAnswer(null);
        filing.setClaimedBy(null);
        filing.setLeaseUntil(null);
        filing.setNextAttemptAt(now);
        filing.setUpdatedAt(now);
        meters.route(decision.route(), decision.reason());
        if (decision.route() == FilingRoute.EMAIL) {
            filing.setState(FilingState.QUEUED);
            return;
        }
        String target = decision.route() == FilingRoute.PUBLIC
                ? destination.getPublicRepository()
                : destination.getPrivateRepository();
        ProductFeedbackIssues.Repository repository = freeze(Objects.requireNonNull(target), decision.route());
        if (repository == null) {
            filing.setState(FilingState.QUEUED);
            filing.setRoute(FilingRoute.EMAIL);
            filing.setReason(FilingReason.DESTINATION_UNAVAILABLE);
            return;
        }
        filing.setRepository(repository.fullName());
        filing.setRepositoryId(repository.id());
        filing.setRepositoryNodeId(repository.nodeId());
        filing.setInstallationId(repository.installationId());
        if (decision.route() == FilingRoute.PUBLIC) {
            filing.setState(FilingState.HELD);
            if (filing.getFiledAfter() == null) filing.setFiledAfter(now.plus(DraftingService.HOLD));
        } else {
            filing.setState(FilingState.QUEUED);
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
