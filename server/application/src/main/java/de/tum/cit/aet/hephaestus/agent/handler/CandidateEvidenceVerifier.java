package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceFileReads;
import de.tum.cit.aet.hephaestus.agent.handler.candidate.Candidate;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.CitationRequirement;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContract;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.BasisToCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationToCheck;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationVerifier;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.spi.CitationProof;
import de.tum.cit.aet.hephaestus.practices.spi.CitationVerificationStatus;
import de.tum.cit.aet.hephaestus.practices.spi.DutyBasis;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.IntroducedBasis;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.StateBasis;
import de.tum.cit.aet.hephaestus.practices.spi.WorsenedBasis;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class CandidateEvidenceVerifier {
    private final EvidenceFileReads files;
    private final ClasspathSourceCatalogRegistry catalogs;
    private final Map<ItemKind, CitationVerifier> verifiers;

    CandidateEvidenceVerifier(
            EvidenceFileReads files, ClasspathSourceCatalogRegistry catalogs, List<CitationVerifier> verifiers) {
        this.files = files;
        this.catalogs = catalogs;
        var byKind = new HashMap<ItemKind, CitationVerifier>();
        for (var verifier : verifiers) {
            for (var kind : verifier.kinds()) {
                if (byKind.putIfAbsent(kind, verifier) != null)
                    throw new IllegalStateException("Multiple citation verifiers handle " + kind.value());
            }
        }
        this.verifiers = Map.copyOf(byKind);
    }

    static final class Unverified extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final CandidateRefusal refusal;
        private final transient @Nullable EvidenceCitation failure;

        Unverified(CandidateRefusal refusal, String reason, @Nullable EvidenceCitation failure) {
            this(refusal, reason, failure, null);
        }

        Unverified(
                CandidateRefusal refusal,
                String reason,
                @Nullable EvidenceCitation failure,
                @Nullable Throwable cause) {
            super(reason, cause, false, false);
            this.refusal = refusal;
            this.failure = failure;
        }

        CandidateRefusal refusal() {
            return refusal;
        }

        @Nullable
        EvidenceCitation failure() {
            return failure;
        }
    }

    Candidate verifyCandidate(
            ReviewJob job,
            ReviewAssignmentSnapshot assignment,
            ReviewKind scope,
            Candidate candidate,
            CapturedEvidence captured) {
        var verifier = verifiers.get(assignment.item().kind());
        List<EvidenceCitation> located = candidate.allCitations();
        Map<EvidenceCitation, EvidenceCitation> verified = new HashMap<>();
        Map<EvidenceCitation, QuoteLocation> locations = new HashMap<>();
        List<CitationToCheck> checks = new ArrayList<>();
        for (int i = 0; i < located.size(); i++) {
            EvidenceCitation citation = located.get(i);
            SourceKind source = citation.sourceKind();
            SourceContract contract = catalogs.current()
                    .source(source)
                    .orElseThrow(() -> new Unverified(
                            CandidateRefusal.MALFORMED_CANDIDATE,
                            "The cited source is not in the source catalog",
                            citation));
            if (!contract.appliesTo(assignment.item().kind().value())
                    || !catalogs.isSourceUsePermitted(source, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)) {
                throw new Unverified(
                        CandidateRefusal.MALFORMED_CANDIDATE,
                        "This source cannot support the assigned review",
                        citation);
            }
            capability(contract.citation().side(), citation.side() != null, "side");
            capability(contract.citation().commit(), citation.commit() != null, "commit");
            if (verifier == null && (citation.side() != null || citation.commit() != null)) {
                throw new Unverified(
                        CandidateRefusal.BASIS_REVISION_UNSUPPORTED,
                        "No item verifier supports this citation reference",
                        citation);
            }
            String sourcePath = citation.sourcePath();
            try {
                captured.requireFile(source, sourcePath);
            } catch (JobDeliveryException unavailable) {
                throw new Unverified(
                        CandidateRefusal.SOURCE_UNAVAILABLE,
                        "The cited source was not captured at this path",
                        citation,
                        unavailable);
            }
            String path = citation.path();
            CitationSide side = citation.side() != null ? citation.side() : CitationSide.NEW;
            String commit = citation.commit();
            var proof = captured.resolve(source, path, side, commit);
            String quote = citation.quote();
            var text = files.readText(job, proof.stagedPath(), proof.sha256())
                    .orElseThrow(() -> new Unverified(
                            CandidateRefusal.SOURCE_UNAVAILABLE, "Captured source text is unavailable", citation));
            int start = atCoordinates(text, quote, citation.startLine(), citation.endLine());
            if (start < 0) {
                start = quote.isEmpty() ? -1 : text.indexOf(quote);
                if (start >= 0 && text.indexOf(quote, start + 1) >= 0) start = -1;
            }
            if (start < 0) {
                throw new Unverified(
                        CandidateRefusal.QUOTE_UNVERIFIED,
                        "The quote is absent or ambiguous in the captured text",
                        citation);
            }
            int firstLine = 1;
            for (int offset = 0; offset < start; offset++) if (text.charAt(offset) == '\n') firstLine++;
            int lastLine = firstLine;
            for (int offset = 0; offset < quote.length() - 1; offset++) if (quote.charAt(offset) == '\n') lastLine++;
            var annotated = new EvidenceCitation(
                    source,
                    captured.witness(sourcePath, proof),
                    path,
                    citation.side(),
                    commit,
                    firstLine,
                    lastLine,
                    quote,
                    new CitationProof(
                            CitationVerificationStatus.VERIFIED,
                            proof.sha256(),
                            ProvenanceDigest.sha256Hex(quote.getBytes(StandardCharsets.UTF_8))));
            verified.put(citation, annotated);
            locations.put(citation, new QuoteLocation(proof.stagedPath(), side, commit, firstLine, lastLine));
            checks.add(new CitationToCheck(i, source.value(), path, side, commit, firstLine, lastLine, quote));
        }
        var example = candidate.example();
        if (example != null) {
            var exampleLocation = Objects.requireNonNull(locations.get(example.citation()));
            for (var problem : candidate.problems()) {
                for (var citation : problem.citations()) {
                    if (exampleLocation.overlaps(Objects.requireNonNull(locations.get(citation))))
                        throw new Unverified(
                                CandidateRefusal.EXAMPLE_OVERLAPS_PROBLEM,
                                "The example cites the place of a problem",
                                example.citation());
                }
            }
        }
        var search = candidate.search();
        if (search != null) requireConsulted(search.consulted(), captured);
        var inapplicability = candidate.inapplicability();
        if (inapplicability != null) requireConsulted(inapplicability.consulted(), captured);
        List<BasisToCheck> bases = new ArrayList<>();
        int citationOffset = candidate.citations().size();
        for (int i = 0; i < candidate.problems().size(); i++) {
            var problem = candidate.problems().get(i);

            if (scope == ReviewKind.SCAN && !(problem.basis() instanceof StateBasis))
                throw new Unverified(CandidateRefusal.BASIS_STATE_UNSUPPORTED, "A scan requires a state basis", null);
            switch (problem.basis()) {
                case StateBasis state -> {
                    if (scope != ReviewKind.SCAN)
                        throw new Unverified(
                                CandidateRefusal.BASIS_STATE_UNSUPPORTED,
                                "State claims are supported only in a scan",
                                null);
                    String itemPath = assignment.item().path();
                    if (itemPath == null
                            || problem.citations().stream().noneMatch(citation -> itemPath.equals(citation.path()))) {
                        throw new Unverified(
                                CandidateRefusal.BASIS_WITNESS_MISSING,
                                "A state claim needs a witness from its assigned file",
                                null);
                    }
                }
                case DutyBasis duty -> {
                    String quote = duty.criterionQuote();
                    if (quote.isBlank()
                            || !assignment.practice().definition().criteria().contains(quote)) {
                        throw new Unverified(
                                CandidateRefusal.DUTY_QUOTE_UNVERIFIED,
                                "The duty quote is absent from the pinned criteria",
                                null);
                    }
                }
                case IntroducedBasis introduced -> {
                    requireChangeVerifier(verifier);
                    bases.add(new BasisToCheck(i, "introduced", citationOffset + introduced.head(), null));
                }
                case WorsenedBasis worsened -> {
                    requireChangeVerifier(verifier);
                    bases.add(new BasisToCheck(
                            i, "worsened", citationOffset + worsened.head(), citationOffset + worsened.base()));
                }
            }
            citationOffset += problem.citations().size();
        }
        if (verifier != null) {
            var version = assignment.version();
            var refusals = verifier.verify(new CitationCheck(
                    job.getWorkspaceId(),
                    assignment.item(),
                    version,
                    version.baseVersion() == null ? null : new ItemVersion(version.baseVersion(), null),
                    files.verificationDirectory(job),
                    checks,
                    bases));
            if (!refusals.isEmpty()) {
                var refusal = refusals.getFirst();
                EvidenceCitation failure =
                        refusal.index() >= 0 && refusal.index() < located.size() ? located.get(refusal.index()) : null;
                throw new Unverified(refusal.reason(), refusal.message(), failure);
            }
        }
        return candidate.withCitations(citation -> Objects.requireNonNull(verified.get(citation)));
    }

    private static int atCoordinates(String text, String quote, int firstLine, int lastLine) {
        if (quote.isEmpty()) return -1;
        int first = 0;
        for (int line = 1; line < firstLine; line++) {
            int newline = text.indexOf('\n', first);
            if (newline < 0) return -1;
            first = newline + 1;
        }
        int end = first;
        for (int line = firstLine; line <= lastLine; line++) {
            int newline = text.indexOf('\n', end);
            if (newline < 0) {
                end = text.length();
                break;
            }
            end = newline + 1;
        }
        int found = text.indexOf(quote, first);
        return found >= 0 && found + quote.length() <= end ? found : -1;
    }

    private record QuoteLocation(
            String stagedPath, CitationSide side, @Nullable String commit, int startLine, int endLine) {
        boolean overlaps(QuoteLocation other) {
            return stagedPath.equals(other.stagedPath)
                    && side == other.side
                    && Objects.equals(commit, other.commit)
                    && startLine <= other.endLine
                    && other.startLine <= endLine;
        }
    }

    private static void requireChangeVerifier(@Nullable CitationVerifier verifier) {
        if (verifier == null)
            throw new Unverified(
                    CandidateRefusal.BASIS_REVISION_UNSUPPORTED,
                    "No item verifier supports change-basis references",
                    null);
    }

    private static void capability(CitationRequirement requirement, boolean present, String field) {
        if ((requirement == CitationRequirement.REQUIRED && !present)
                || (requirement == CitationRequirement.FORBIDDEN && present)) {
            throw new Unverified(
                    CandidateRefusal.MALFORMED_CANDIDATE,
                    "The source catalog does not permit this " + field + " shape",
                    null);
        }
    }

    private static void requireConsulted(List<SourceKind> consulted, CapturedEvidence captured) {
        for (var source : consulted) {
            if (!captured.availableSources().contains(source))
                throw new Unverified(
                        CandidateRefusal.SOURCE_UNAVAILABLE, "A warrant names a source that was not captured", null);
        }
    }
}
