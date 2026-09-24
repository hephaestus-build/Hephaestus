package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.AdoptedBaseSource;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeCatalogInstallation;
import de.tum.cit.aet.hephaestus.practices.PracticeCatalogInstallationRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnServerRole
@WorkspaceAgnostic("Repairs migrated fingerprints and links eligible catalog installations")
public class CatalogProvenanceBackfill {

    private final PracticeCatalogInstallationRepository installationRepository;
    private final PracticeRepository practiceRepository;
    private final PracticeGroupRepository practiceGroupRepository;
    private final PracticeRevisionRepository revisionRepository;
    private final PracticeRevisionService revisionService;
    private final CuratedCatalogService curatedCatalogService;
    private final BundledPracticeCatalogLoader bundledCatalogLoader;
    private final CuratedPracticeOverrideRepository practiceOverrideRepository;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    public Stamped run() {
        alignVersionedEvidence();
        fingerprintMigratedRevisions();
        Map<String, PracticeDefinition> bundled = bundledCatalogLoader.catalog().practices().stream()
                .collect(Collectors.toMap(
                        BundledPracticeCatalog.BundledEntry::slug, BundledPracticeCatalog.BundledEntry::definition));
        backfillAdoptedBases(bundled);
        List<Long> pending = installationRepository.findWorkspaceIdsAwaitingProvenanceLink();
        if (pending.isEmpty()) {
            return new Stamped(0, 0);
        }
        EffectiveCatalog catalog = curatedCatalogService.catalog();
        Stamped total = new Stamped(0, 0);
        int completed = 0;
        for (Long workspaceId : pending) {
            try {
                total = total.plus(transactionOperations.execute(ignored -> stamp(workspaceId, catalog, bundled)));
                completed++;
            } catch (RuntimeException exception) {
                log.error("Could not stamp catalog provenance: workspaceId={}", workspaceId, exception);
            }
        }
        log.info(
                "Stamped catalog provenance for {} workspace(s): {} practices, {} groups",
                completed,
                total.practices(),
                total.groups());
        return total;
    }

    private void backfillAdoptedBases(Map<String, PracticeDefinition> bundled) {
        for (Long practiceId : practiceRepository.findIdsMissingAdoptedBase()) {
            try {
                transactionOperations.executeWithoutResult(ignored -> {
                    Practice practice = practiceRepository.findById(practiceId).orElseThrow();
                    if (practice.getAdoptedBase() != null) {
                        return;
                    }
                    setRecoveredBase(practice, bundled);
                    practiceRepository.save(practice);
                });
            } catch (RuntimeException exception) {
                log.error("Could not backfill adopted base: practiceId={}", practiceId, exception);
            }
        }
        for (String slug : practiceOverrideRepository.findSlugsMissingAdoptedBase()) {
            try {
                transactionOperations.executeWithoutResult(ignored -> {
                    CuratedPracticeOverride override =
                            practiceOverrideRepository.findBySlug(slug).orElseThrow();
                    override.backfillBase(bundled.get(slug));
                    practiceOverrideRepository.save(override);
                });
            } catch (RuntimeException exception) {
                log.error("Could not backfill instance base: slug={}", slug, exception);
            }
        }
    }

    private void setRecoveredBase(Practice practice, Map<String, PracticeDefinition> bundled) {
        String slug = Objects.requireNonNull(practice.getSourceCuratedSlug());
        PracticeDefinition candidate = bundled.get(slug);
        if (candidate != null && candidate.provenanceFingerprint(slug).equals(practice.getSourceCuratedFingerprint())) {
            practice.setAdoptedBase(candidate);
            practice.setAdoptedBaseSource(AdoptedBaseSource.BUNDLED_FINGERPRINT_MATCH);
        } else {
            practice.setAdoptedBase(PracticeDefinition.from(practice));
            practice.setAdoptedBaseSource(AdoptedBaseSource.CURRENT_DEFINITION);
        }
    }

    private void alignVersionedEvidence() {
        EffectiveCatalog catalog = curatedCatalogService.catalog();
        for (Long practiceId : practiceRepository.findSourceAlignedV1PracticeIds()) {
            try {
                transactionOperations.executeWithoutResult(ignored -> {
                    Practice managed = practiceRepository.findById(practiceId).orElseThrow();
                    String sourceSlug = Objects.requireNonNull(managed.getSourceCuratedSlug());
                    catalog.practice(sourceSlug).ifPresent(entry -> {
                        PracticeDefinition effective = entry.effective();
                        PracticeDefinition aligned = new PracticeDefinition(
                                managed.getName(),
                                managed.getBindings(),
                                managed.getCriteria(),
                                managed.getPrecomputeScript(),
                                effective.automatedReviewPolicy(),
                                managed.getWhyItMatters(),
                                managed.getWhatGoodLooksLike(),
                                managed.getGroup() == null
                                        ? null
                                        : managed.getGroup().getSlug(),
                                managed.getDeliveryBehavior());
                        if (!aligned.provenanceFingerprint(entry.slug())
                                .equals(effective.provenanceFingerprint(entry.slug()))) {
                            return;
                        }
                        managed.setAutomatedReviewPolicy(effective.automatedReviewPolicy());
                        managed.setSourceCuratedFingerprint(effective.provenanceFingerprint(entry.slug()));
                        revisionService.append(managed);
                    });
                });
            } catch (RuntimeException exception) {
                log.error("Could not align catalog evidence: practiceId={}", practiceId, exception);
            }
        }
    }

    private void fingerprintMigratedRevisions() {
        for (Long workspaceId : revisionRepository.findWorkspaceIdsWithDefinitionRevisionsMissingFingerprint()) {
            try {
                transactionOperations.executeWithoutResult(ignored -> fingerprintMigratedRevisions(workspaceId));
            } catch (RuntimeException exception) {
                log.error("Could not fingerprint migrated practice revisions: workspaceId={}", workspaceId, exception);
            }
        }
    }

    public record Stamped(int practices, int groups) {
        Stamped plus(Stamped other) {
            return other == null ? this : new Stamped(practices + other.practices(), groups + other.groups());
        }
    }

    private Stamped stamp(Long workspaceId, EffectiveCatalog catalog, Map<String, PracticeDefinition> bundled) {
        PracticeCatalogInstallation installation =
                installationRepository.findByWorkspaceIdForUpdate(workspaceId).orElse(null);
        if (installation == null || installation.getProvenanceLinkedAt() != null) {
            return new Stamped(0, 0);
        }
        int groups = stampGroups(workspaceId, catalog);
        int practices = stampPractices(workspaceId, catalog, bundled);
        installation.markProvenanceLinked(clock.instant());
        installationRepository.save(installation);
        return new Stamped(practices, groups);
    }

    private void fingerprintMigratedRevisions(Long workspaceId) {
        for (PracticeRevision revision : revisionRepository.findDefinitionRevisionsMissingFingerprint(workspaceId)) {
            revisionRepository.setReviewRuleFingerprint(
                    Objects.requireNonNull(revision.getId()), revision.computeReviewRuleFingerprint());
        }
    }

    private int stampPractices(Long workspaceId, EffectiveCatalog catalog, Map<String, PracticeDefinition> bundled) {
        int stamped = 0;
        for (Practice practice : practiceRepository.findAllForCatalog(workspaceId)) {
            if (practice.getSourceCuratedSlug() != null || practice.getCurrentRevision() == null) {
                continue;
            }
            String fingerprint = PracticeDefinition.from(practice).provenanceFingerprint(practice.getSlug());
            boolean matchesCatalog = catalog.practices().stream()
                    .filter(entry -> entry.slug().equals(practice.getSlug()))
                    .findFirst()
                    .map(entry -> entry.effective()
                            .provenanceFingerprint(entry.slug())
                            .equals(fingerprint))
                    .orElse(false);
            if (!matchesCatalog) {
                continue;
            }
            practice.setSourceCuratedSlug(practice.getSlug());
            practice.setSourceCuratedFingerprint(fingerprint);
            setRecoveredBase(practice, bundled);
            practiceRepository.save(practice);
            stamped++;
        }
        return stamped;
    }

    private int stampGroups(Long workspaceId, EffectiveCatalog catalog) {
        int stamped = 0;
        for (PracticeGroup group :
                practiceGroupRepository.findByWorkspaceIdOrderByDisplayOrderAscNameAsc(workspaceId)) {
            if (group.getSourceCuratedSlug() != null) {
                continue;
            }
            String fingerprint = GroupDefinition.from(group).provenanceFingerprint(group.getSlug());
            boolean matchesCatalog = catalog.groups().stream()
                    .filter(entry -> entry.slug().equals(group.getSlug()))
                    .findFirst()
                    .map(entry -> entry.effective()
                            .provenanceFingerprint(entry.slug())
                            .equals(fingerprint))
                    .orElse(false);
            if (!matchesCatalog) {
                continue;
            }
            group.setSourceCuratedSlug(group.getSlug());
            group.setSourceCuratedFingerprint(fingerprint);
            practiceGroupRepository.save(group);
            stamped++;
        }
        return stamped;
    }
}
