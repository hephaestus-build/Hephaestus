package de.tum.cit.aet.hephaestus.practices.advice;

import static de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal.EVIDENCE_INELIGIBLE;
import static de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal.GATE_REFUSED;
import static de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal.NOT_ENABLED;
import static de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal.NOT_MENTOR_REVIEW;
import static de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal.OWNER_DISMISSED;
import static de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal.OWNER_UNAVAILABLE;
import static de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal.REVIEW_ALREADY_WROTE;
import static de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal.TEXT_INVALID;
import static de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal.VERSION_MOVED;
import static java.nio.charset.StandardCharsets.UTF_8;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.advice.PracticeAdviceReceipt.Kind;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceErasureListener;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite.Refused;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite.Written;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceRef;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackProducer;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeAdviceWrites;
import de.tum.cit.aet.hephaestus.practices.spi.RetireAdvice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewFeedbackProducers;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviseAdvice;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes lasting advice for the mentor's review handler (ADR 0160, PRACTICE-ADVICE table A). The first refusal that
 * applies wins, and a refusal writes nothing.
 *
 * <p>A write locks the owner's person-data write fence, then its observations and problems ({@code FOR KEY SHARE}, in
 * id order), then the entry. {@link AdviceRedaction} locks the observations first too, so a write that waits for a
 * deletion finds an observation gone, and a write that commits first loses its text in the deleting transaction.
 */
@Slf4j
@Service
@ConditionalOnServerRole
@Transactional
public class PracticeAdviceWriter implements PracticeAdviceWrites {
    private final PracticeAdviceProperties properties;
    /** Empty until the runtime records each review's producer (RV1). */
    private final List<ReviewFeedbackProducers> producers;
    /** Empty until the conversation side hears that advice went stale (#5278). */
    private final List<AdviceErasureListener> listeners;

    private final AccountDirectory accounts;
    private final AccountMembershipQuery memberships;
    private final PersonDataWriteFence fence;
    private final ReviewSnapshotQuery reviews;
    private final FeedbackGate gate;
    private final FeedbackBasisRepository basis;
    private final AdviceRows rows;

    PracticeAdviceWriter(
            PracticeAdviceProperties properties,
            List<ReviewFeedbackProducers> producers,
            List<AdviceErasureListener> listeners,
            AccountDirectory accounts,
            AccountMembershipQuery memberships,
            PersonDataWriteFence fence,
            ReviewSnapshotQuery reviews,
            FeedbackGate gate,
            FeedbackBasisRepository basis,
            AdviceRows rows) {
        this.properties = properties;
        this.producers = List.copyOf(producers);
        this.listeners = List.copyOf(listeners);
        this.accounts = accounts;
        this.memberships = memberships;
        this.fence = fence;
        this.reviews = reviews;
        this.gate = gate;
        this.basis = basis;
        this.rows = rows;
        if (properties.allowWithoutErasureListener()) {
            log.warn("hephaestus.practices.advice.allow-without-erasure-listener is on: listed workspaces write advice"
                    + " although no conversation hears that advice lost its evidence. Use it only in a synthetic proof"
                    + " environment.");
        }
    }

    @Override
    public AdviceWrite revise(ReviseAdvice command) {
        long ws = command.workspaceId();
        var admission = admission(ws, command.accountId(), command.reviewId());
        if (admission != null) return refused(admission);
        var review = reviews.find(ws, command.reviewId());
        if (review.isEmpty()) return refused(NOT_MENTOR_REVIEW);

        var refs = new ArrayList<EvidenceRef>(command.citations());
        refs.addAll(command.otherObservations());
        var observed = rows.shareObservations(
                ws, refs.stream().map(EvidenceRef::resultId).distinct().toList());
        var problemsById = rows.shareProblems(
                ws,
                refs.stream()
                        .map(EvidenceRef::problemId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        var entry =
                rows.lockEntry(ws, command.accountId(), command.practiceId()).orElse(null);
        var digest = digest(
                Kind.REVISE.name(),
                String.valueOf(command.practiceId()),
                String.valueOf(command.seenVersion()),
                command.text(),
                refs(command.citations()),
                refs(command.otherObservations()));
        if (entry != null) {
            var earlier = earlierDecision(entry, command.reviewId(), digest);
            if (earlier != null) return earlier;
        }
        if (!Objects.equals(command.seenVersion(), entry == null ? null : entry.getVersion())) {
            return refused(VERSION_MOVED);
        }
        if (!eligible(command, refs, observed, problemsById)) return refused(EVIDENCE_INELIGIBLE);
        var cited = command.citations().stream()
                .map(ref -> Objects.requireNonNull(observed.get(ref.resultId())))
                .distinct()
                .toList();
        var check = gate.evaluateComposition(
                review.orElseThrow(), command.accountId(), cited, FeedbackDestination.PRACTICE_PAGE);
        if (!check.allowed()) return new Refused(GATE_REFUSED, check.reason());
        if (basis.hasIncorrectResults(ws, observed.keySet())) {
            return new Refused(GATE_REFUSED, FeedbackStopReason.RESULT_MARKED_INCORRECT);
        }
        if (entry != null && !admitsAfterDismissal(entry, cited)) return refused(OWNER_DISMISSED);
        if (!AdviceText.admits(command.text())) return refused(TEXT_INVALID);

        if (entry == null) {
            var id = UUID.randomUUID();
            if (rows.insertFirst(id, ws, command.accountId(), command.practiceId())) {
                return new Written(id, rows.writeRevision(command, id, 0, null, digest));
            }
            // Another write created the entry after this one read none.
            var existing = rows.lockEntry(ws, command.accountId(), command.practiceId())
                    .orElseThrow();
            var earlier = earlierDecision(existing, command.reviewId(), digest);
            return earlier != null ? earlier : refused(VERSION_MOVED);
        }
        return new Written(
                entry.getId(),
                rows.writeRevision(command, entry.getId(), entry.getVersion(), entry.getLatestRevision(), digest));
    }

    @Override
    public AdviceWrite retire(RetireAdvice command) {
        long ws = command.workspaceId();
        var admission = admission(ws, command.accountId(), command.reviewId());
        if (admission != null) return refused(admission);
        var entry =
                rows.lockEntry(ws, command.accountId(), command.practiceId()).orElse(null);
        if (entry == null) return refused(VERSION_MOVED);
        var digest = digest(
                Kind.RETIRE.name(),
                String.valueOf(command.practiceId()),
                String.valueOf(command.seenVersion()),
                command.reason().name());
        var earlier = earlierDecision(entry, command.reviewId(), digest);
        if (earlier != null) return earlier;
        if (entry.getVersion() != command.seenVersion()) return refused(VERSION_MOVED);
        return new Written(entry.getId(), rows.retire(entry, command, digest));
    }

    /** Table A rows 1 to 3. The fence stays held until this transaction ends. */
    private @Nullable AdviceRefusal admission(long ws, long accountId, UUID reviewId) {
        if (!properties.writableWorkspaceIds().contains(ws)
                || producers.size() != 1
                || (listeners.isEmpty() && !properties.allowWithoutErasureListener())) {
            return NOT_ENABLED;
        }
        // An unclaimed account gets no advice, so an absorption never moves it.
        if (!fence.holdForAccountWrite(accountId)
                || !accounts.find(accountId)
                        .map(account -> account.status() == Account.Status.ACTIVE)
                        .orElse(false)
                || !memberships.isActiveVisibleMember(ws, accountId)) {
            return OWNER_UNAVAILABLE;
        }
        return producers
                        .getFirst()
                        .producer(ws, reviewId)
                        .filter(FeedbackProducer.MENTOR::equals)
                        .isPresent()
                ? null
                : NOT_MENTOR_REVIEW;
    }

    /** Table A rows 7 and 8: every reference is a live observation or problem of the owner, without repeats. */
    private static boolean eligible(
            ReviseAdvice command, List<EvidenceRef> refs, Map<UUID, Result> observed, Map<UUID, Problem> problems) {
        if (command.citations().isEmpty() || new HashSet<>(refs).size() != refs.size()) return false;
        for (var ref : refs) {
            var observation = observed.get(ref.resultId());
            if (observation == null || !Objects.equals(observation.getDeveloperId(), command.accountId())) {
                return false;
            }
            var problemId = ref.problemId();
            if (problemId != null) {
                var problem = problems.get(problemId);
                if (problem == null || !problem.getResultId().equals(ref.resultId())) return false;
            }
        }
        return command.citations().stream()
                .anyMatch(ref ->
                        Objects.requireNonNull(observed.get(ref.resultId())).getPracticeId() == command.practiceId());
    }

    /** Table A row 10: after a dismissal, only a citation of a new item, recorded after it, brings advice back. */
    private static boolean admitsAfterDismissal(PracticeAdvice entry, List<Result> cited) {
        var since = entry.getDismissedAt();
        if (since == null) return true;
        var items = entry.getDismissedItems();
        Set<Long> dismissed =
                items == null ? Set.of() : Arrays.stream(items).boxed().collect(Collectors.toSet());
        return cited.stream()
                .anyMatch(observation -> !dismissed.contains(observation.getItemId())
                        && observation.getRecordedAt().isAfter(since));
    }

    /** Table A rows 4 and 5. The digest covers the kind, so a retire never matches a revise. */
    private @Nullable AdviceWrite earlierDecision(PracticeAdvice entry, UUID reviewId, byte[] digest) {
        return rows.receipt(entry.getId(), entry.getWorkspaceId(), reviewId)
                .<AdviceWrite>map(receipt -> Arrays.equals(receipt.getContentDigest(), digest)
                        ? new Written(entry.getId(), receipt.getVersion())
                        : refused(REVIEW_ALREADY_WROTE))
                .orElse(null);
    }

    private static String refs(List<EvidenceRef> refs) {
        return refs.stream().map(ref -> ref.resultId() + "/" + ref.problemId()).collect(Collectors.joining(","));
    }

    /** SHA-256 over the decision's parts, each with its length, so two decisions never share a digest by framing. */
    private static byte[] digest(String... parts) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                var bytes = part.getBytes(UTF_8);
                sha.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                sha.update(bytes);
            }
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Refused refused(AdviceRefusal reason) {
        return new Refused(reason, null);
    }
}
