package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The retained occasion evidence of SCM work: it builds the record that an occasion retains, under the person-data
 * write fence and the size limit, for its caller to store. It also completes a pending occasion's record with the diff
 * base its version adds, and reads back the record that exactly one occasion retained.
 */
@Component
@Slf4j
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmRetainedOccasionEvidence {
    static final String NOT_OBSERVED = "NOT_OBSERVED";
    static final String SUPPRESSED = "SUPPRESSED";
    static final String SIZE_LIMIT = "SIZE_LIMIT";

    private final ItemEventService events;
    private final PersonDataWriteFence fence;
    private final ObjectMapper mapper;

    /**
     * The serialized record of an occasion, or an {@link ItemEventInput#unretained} statement of why it is not kept: a
     * copied person's content may not be written ({@code SUPPRESSED}), or the record exceeds what an occasion may
     * retain ({@code SIZE_LIMIT}).
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ItemCaptureException.class)
    public String retain(ScmOccasionEvidence evidence, long itemId) {
        var participants = evidence.participants();
        // The erasure of any of these people finishes before, or waits until this write commits and then finds it by
        // their native identity, which the record keeps.
        if (!participants.isEmpty()
                && !fence.holdForWrite(participants.stream()
                        .map(ScmOccasionEvidence.Participant::identity)
                        .toList())) return ItemEventInput.unretained(SUPPRESSED);
        String json = mapper.writeValueAsString(evidence);
        if (!ItemEventInput.fits(json)) {
            log.info("Occasion evidence not retained: reason=sizeLimit, itemId={}", itemId);
            return ItemEventInput.unretained(SIZE_LIMIT);
        }
        return json;
    }

    /**
     * The evidence of a pending occasion completed with the diff base its version adds. The caller pins the version
     * only while the occasion's fence holds; the record's state, roles, people and words stay as first retained. A
     * statement that nothing was kept stays as it is, and null stays null.
     */
    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    public @Nullable String complete(@Nullable String retained, ItemVersion version) {
        if (retained == null) return null;
        ScmOccasionEvidence evidence;
        try {
            if (mapper.readTree(retained).has(ItemEventInput.UNRETAINED)) return retained;
            evidence = mapper.readValue(retained, ScmOccasionEvidence.class);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Retained occasion evidence is unreadable", unreadable);
        }
        try {
            return mapper.writeValueAsString(evidence.pinned(version));
        } catch (IllegalArgumentException notThisOccasion) {
            // The unchanged pending fence already proved the occasion's material. A different retained record is
            // inconsistent, not an original that was never kept; keep the fault visible for repair and retry.
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED,
                    "Retained occasion evidence does not match its version",
                    notThisOccasion);
        }
    }

    /**
     * The record exactly this occasion of {@code item} retained. Without one it refuses with
     * {@link ItemCaptureCode#OCCASION_EVIDENCE_UNAVAILABLE}; a record that cannot be read is a fault.
     */
    ScmOccasionEvidence occasion(ItemRef item, UUID eventId) {
        var retained = events.evidence(item.workspaceId(), item.itemId(), eventId);
        var json = retained.record();
        if (json == null)
            throw new ItemCaptureException(
                    ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE,
                    "The occasion kept no record of the work: " + retained.unretained());
        try {
            return mapper.readValue(json, ScmOccasionEvidence.class);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Retained occasion evidence is unreadable", unreadable);
        }
    }
}
