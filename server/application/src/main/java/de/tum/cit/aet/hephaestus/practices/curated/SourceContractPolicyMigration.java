package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
@RequiredArgsConstructor
@ConditionalOnServerRole
@WorkspaceAgnostic("Upgrades installed source-contract policies without rewriting historical revisions")
public class SourceContractPolicyMigration {
    private static final SourceContractVersion PREVIOUS = ArtifactSourceCatalogRegistry.PREVIOUS_VERSION;
    private static final SourceContractVersion CURRENT = ArtifactSourceCatalogRegistry.CURRENT_VERSION;

    private final PracticeRepository practiceRepository;
    private final PracticeRevisionService revisionService;
    private final CuratedPracticeOverrideRepository overrideRepository;
    private final CuratedCatalogLock catalogLock;
    private final TransactionOperations transactions;
    private final Clock clock;

    public void run() {
        transactions.executeWithoutResult(ignored -> {
            catalogLock.acquire();
            for (var override : overrideRepository.findAll()) {
                var definition = override.definition();
                if (definition == null
                        || !definition
                                .automatedReviewPolicy()
                                .sourceContractVersion()
                                .equals(PREVIOUS)) {
                    continue;
                }
                override.write(
                        new PracticeDefinition(
                                definition.name(),
                                definition.bindings(),
                                definition.criteria(),
                                definition.precomputeScript(),
                                upgrade(definition.automatedReviewPolicy()),
                                definition.whyItMatters(),
                                definition.whatGoodLooksLike(),
                                definition.groupSlug()),
                        override.getAcceptedBundledDigest(),
                        clock.instant());
                overrideRepository.save(override);
            }
        });
        for (Long id : practiceRepository.findIdsBySourceContractVersion(PREVIOUS.value())) {
            transactions.executeWithoutResult(ignored -> {
                var practice = practiceRepository.findByIdForUpdate(id).orElse(null);
                if (practice == null
                        || !practice.getAutomatedReviewPolicy()
                                .sourceContractVersion()
                                .equals(PREVIOUS)) {
                    return;
                }
                String sourceSlug = practice.getSourceCuratedSlug();
                boolean sourceAligned = sourceSlug != null
                        && PracticeDefinition.from(practice)
                                .provenanceFingerprint(sourceSlug)
                                .equals(practice.getSourceCuratedFingerprint());
                practice.setAutomatedReviewPolicy(upgrade(practice.getAutomatedReviewPolicy()));
                if (sourceAligned && sourceSlug != null) {
                    practice.setSourceCuratedFingerprint(
                            PracticeDefinition.from(practice).provenanceFingerprint(sourceSlug));
                }
                revisionService.append(practice);
            });
        }
    }

    private static PracticeAutomatedReviewPolicy upgrade(PracticeAutomatedReviewPolicy policy) {
        return new PracticeAutomatedReviewPolicy(
                CURRENT,
                policy.automatedReview(),
                policy.whenEvidenceIsInsufficient(),
                policy.knownLimitations(),
                policy.insufficiencyReason());
    }
}
