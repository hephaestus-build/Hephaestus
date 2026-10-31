package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ProductFeedbackDestinationsDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.RepositoryCheck;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.UpdateProductFeedbackDestinationsDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** The operator's destination settings, their checks against GitHub, and the public repository's issue snapshot. */
@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class DestinationService {
    private static final Logger log = LoggerFactory.getLogger(DestinationService.class);

    private final ProductFeedbackDestinationRepository repository;
    private final DestinationSettings settings;
    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackIssues issues;
    private final TransactionTemplate transactions;
    private final Clock clock;

    DestinationService(
            ProductFeedbackDestinationRepository repository,
            DestinationSettings settings,
            ProductFeedbackFilingRepository filings,
            ProductFeedbackIssues issues,
            TransactionTemplate transactions,
            Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.filings = filings;
        this.issues = issues;
        this.transactions = transactions;
        this.clock = clock;
    }

    ProductFeedbackDestinationsDTO get() {
        ProductFeedbackDestination destination = settings.current();
        Optional<ProductFeedbackIssues.Repository> publicRepository = lookup(destination.getPublicRepository());
        Optional<ProductFeedbackIssues.Repository> privateRepository = lookup(destination.getPrivateRepository());
        Instant now = clock.instant();
        return new ProductFeedbackDestinationsDTO(
                destination.getPublicRepository(),
                destination.getPrivateRepository(),
                destination.getLlmModelId(),
                destination.getDailyDraftLimit(),
                destination.isPaused(),
                destination.getNoticeVersion(),
                check(destination.getPublicRepository(), publicRepository, false, privateRepository),
                check(destination.getPrivateRepository(), privateRepository, true, publicRepository),
                filings.countDraftedSince(now.truncatedTo(ChronoUnit.DAYS)),
                filings.countByState(FilingState.STOPPED),
                filings.lastFiledAt().orElse(null),
                filings.oldestWaiting().map(ProductFeedbackFiling::getCreatedAt).orElse(null),
                destination.getSnapshotAt());
    }

    ProductFeedbackDestinationsDTO update(UpdateProductFeedbackDestinationsDTO request, long accountId) {
        String publicName = blankToNull(request.publicRepository());
        String privateName = blankToNull(request.privateRepository());
        if (privateName != null) {
            var found = issues.repository(privateName)
                    .orElseThrow(() -> invalid(
                            "The app cannot reach " + privateName + ". Install it there with Issues: read and write."));
            if (!found.isPrivate()) throw invalid(privateName + " is public. The private repository must be private.");
        }
        if (publicName != null && issues.repository(publicName).isEmpty())
            throw invalid("The app cannot reach " + publicName + ". Install it there with Issues: read and write.");
        if (publicName != null && publicName.equalsIgnoreCase(privateName))
            throw invalid("The public and private repositories must differ.");
        transactions.executeWithoutResult(status -> {
            ProductFeedbackDestination destination = repository
                    .findById(ProductFeedbackDestination.SINGLETON)
                    .orElseGet(ProductFeedbackDestination::new);
            boolean publicChanged = !Objects.equals(lower(destination.getPublicRepository()), lower(publicName));
            destination.setPublicRepository(publicName);
            destination.setPrivateRepository(privateName);
            destination.setLlmModelId(request.llmModelId());
            destination.setDailyDraftLimit(request.dailyDraftLimit());
            destination.setPaused(request.paused());
            if (request.newNoticeVersion()) destination.setNoticeVersion(destination.getNoticeVersion() + 1);
            if (publicChanged) {
                destination.setSnapshot(null);
                destination.setSnapshotAt(null);
            }
            destination.setUpdatedAt(clock.instant());
            destination.setUpdatedByAccountId(accountId);
            repository.save(destination);
        });
        refreshSnapshot();
        return get();
    }

    /** Reads the public repository's open issues for duplicate suggestions. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    @WorkspaceAgnostic("The instance's one destination row; no workspace")
    void refreshSnapshot() {
        String name = settings.current().getPublicRepository();
        if (name == null) return;
        try {
            var found = issues.repository(name).orElse(null);
            if (found == null) return;
            var open = issues.openIssues(found, Snapshot.LIMIT).stream()
                    .map(issue -> new Snapshot.Entry(issue.number(), issue.title()))
                    .toList();
            storeSnapshot(name, open);
        } catch (RuntimeException failed) {
            log.warn(
                    "Product feedback snapshot refresh failed: error={}",
                    failed.getClass().getSimpleName());
        }
    }

    private void storeSnapshot(String name, List<Snapshot.Entry> open) {
        transactions.executeWithoutResult(status -> repository
                .findById(ProductFeedbackDestination.SINGLETON)
                .filter(destination -> name.equalsIgnoreCase(destination.getPublicRepository()))
                .ifPresent(destination -> {
                    destination.setSnapshot(Snapshot.write(open));
                    destination.setSnapshotAt(clock.instant());
                }));
    }

    private Optional<ProductFeedbackIssues.Repository> lookup(@Nullable String name) {
        if (name == null) return Optional.empty();
        try {
            return issues.repository(name);
        } catch (RuntimeException failed) {
            return Optional.empty();
        }
    }

    private static RepositoryCheck check(
            @Nullable String name,
            Optional<ProductFeedbackIssues.Repository> found,
            boolean mustBePrivate,
            Optional<ProductFeedbackIssues.Repository> other) {
        if (name == null) return RepositoryCheck.UNSET;
        if (found.isEmpty()) return RepositoryCheck.MISSING;
        if (mustBePrivate && !found.get().isPrivate()) return RepositoryCheck.NOT_PRIVATE;
        if (other.isPresent()
                && !owner(found.get().fullName()).equals(owner(other.get().fullName())))
            return RepositoryCheck.DIFFERENT_OWNER;
        if (!mustBePrivate && found.get().isPrivate()) return RepositoryCheck.NOT_PUBLIC;
        return RepositoryCheck.INSTALLED;
    }

    private static String owner(String fullName) {
        return fullName.substring(0, fullName.indexOf('/')).toLowerCase(Locale.ROOT);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static @Nullable String lower(@Nullable String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
