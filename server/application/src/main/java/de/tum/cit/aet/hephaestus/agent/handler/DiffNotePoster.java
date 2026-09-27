package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliverySuppressedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DiffNotePoster {

    private static final Logger log = LoggerFactory.getLogger(DiffNotePoster.class);

    static final String HEPHAESTUS_MARKER = "<!-- hephaestus-diff-note -->";

    private final PullRequestCommentPoster commentPoster;
    private final PracticeFeedbackCommentFormatter commentFormatter;
    private final Map<IntegrationKind, InlineFeedbackChannel> channels;

    DiffNotePoster(
            PullRequestCommentPoster commentPoster,
            PracticeFeedbackCommentFormatter commentFormatter,
            List<InlineFeedbackChannel> inlineFeedbackChannels) {
        this.commentPoster = commentPoster;
        this.commentFormatter = commentFormatter;
        EnumMap<IntegrationKind, InlineFeedbackChannel> map = new EnumMap<>(IntegrationKind.class);
        for (InlineFeedbackChannel channel : inlineFeedbackChannels) {
            InlineFeedbackChannel previous = map.putIfAbsent(channel.kind(), channel);
            if (previous != null) {
                throw new IllegalStateException("Duplicate InlineFeedbackChannel for kind " + channel.kind()
                        + ": "
                        + previous.getClass().getName()
                        + " conflicts with "
                        + channel.getClass().getName());
            }
        }
        this.channels = map;
    }

    DiffNoteResult reconcileInlineNotes(AgentJob job, List<DiffNote> diffNotes) {
        return reconcileInlineNotes(job, diffNotes, null);
    }

    DiffNoteResult reconcileApprovedInlineNotes(AgentJob job, UUID feedbackId, List<DiffNote> diffNotes) {
        return reconcileInlineNotes(job, diffNotes, feedbackId);
    }

    /**
     * The notes an earlier attempt may have posted without its result being recorded, looked up on the provider
     * without writing. Complete only when every such note is found: a note that is not there yet may still arrive,
     * and a lookup that cannot answer proves nothing either. The notes it did find are kept either way.
     */
    InlineLookup findUnacknowledged(
            AgentJob job,
            List<DiffNote> diffNotes,
            @Nullable UUID packageId,
            List<InlineFeedbackChannel.DeliveredSignal> acknowledged) {
        Set<String> known = new HashSet<>();
        for (InlineFeedbackChannel.DeliveredSignal signal : acknowledged) {
            // An adapter reports FAILED for a write whose response was lost, too, so only a posted note is known.
            if (signal.deliveryKey() != null && signal.disposition() != InlineFeedbackChannel.Disposition.FAILED) {
                known.add(signal.deliveryKey());
            }
        }
        List<InlineFeedbackChannel.InlineFeedback> unacknowledged = mapObservations(diffNotes, packageId).stream()
                .filter(item -> !known.contains(item.deliveryKey()))
                .toList();
        if (unacknowledged.isEmpty()) {
            return new InlineLookup(List.of(), true);
        }
        IntegrationKind kind = job.getIntegrationKind();
        if (kind == null) {
            return InlineLookup.INCONCLUSIVE;
        }
        InlineFeedbackChannel channel = channels.get(kind);
        if (channel == null) {
            return InlineLookup.INCONCLUSIVE;
        }
        @Nullable List<InlineFeedbackChannel.DeliveredSignal> found;
        try {
            found = channel.findPosted(
                    commentPoster.buildTarget(job, kind, job.getWorkspace().getId()),
                    unacknowledged,
                    packageId != null);
        } catch (RuntimeException e) {
            return InlineLookup.INCONCLUSIVE;
        }
        if (found == null) {
            return InlineLookup.INCONCLUSIVE;
        }
        return new InlineLookup(found, found.size() == unacknowledged.size());
    }

    record InlineLookup(List<InlineFeedbackChannel.DeliveredSignal> found, boolean complete) {
        static final InlineLookup INCONCLUSIVE = new InlineLookup(List.of(), false);
    }

    private DiffNoteResult reconcileInlineNotes(AgentJob job, List<DiffNote> diffNotes, @Nullable UUID packageId) {
        IntegrationKind kind =
                Objects.requireNonNull(job.getIntegrationKind(), "AgentJob.integrationKind must not be null");
        InlineFeedbackChannel channel = channels.get(kind);
        if (channel == null) {
            throw new JobDeliveryException("No InlineFeedbackChannel wired for kind " + kind
                    + " — check that the vendor integration is enabled and its channel bean is registered");
        }

        SummaryChannel.FeedbackTarget target =
                commentPoster.buildTarget(job, kind, job.getWorkspace().getId());

        List<InlineFeedbackChannel.InlineFeedback> observations =
                mapObservations(diffNotes == null ? List.of() : diffNotes, packageId);

        if (observations.isEmpty()) {
            try {
                channel.clearStaleFeedback(target, HEPHAESTUS_MARKER);
            } catch (OutboundEgressSuppressedException e) {
                throw new JobDeliverySuppressedException(e.toString(), e);
            } catch (RuntimeException e) {
                throw new JobDeliveryException(e.toString(), e);
            }
            return new DiffNoteResult(0, 0, List.of());
        }

        try {
            InlineFeedbackChannel.InlineResult result = packageId == null
                    ? channel.postInlineFeedback(target, observations)
                    : channel.postImmutablePackage(target, observations);
            log.debug(
                    "Inline observation delivery: kind={}, posted={}, failed={}, jobId={}",
                    kind,
                    result.posted(),
                    result.failed(),
                    job.getId());
            return new DiffNoteResult(
                    result.posted(),
                    result.failed(),
                    result.signals(),
                    result.suppressed(),
                    result.suppressedDeliveryKeys());
        } catch (OutboundEgressSuppressedException e) {
            throw new JobDeliverySuppressedException(e.toString(), e);
        } catch (FeedbackDeliveryException e) {
            throw new JobDeliveryException(e.toString(), e);
        }
    }

    private List<InlineFeedbackChannel.InlineFeedback> mapObservations(
            List<DiffNote> diffNotes, @Nullable UUID packageId) {
        List<InlineFeedbackChannel.InlineFeedback> observations = new ArrayList<>(diffNotes.size());
        for (int index = 0; index < diffNotes.size(); index++) {
            DiffNote note = diffNotes.get(index);
            String sanitized = PullRequestCommentPoster.sanitize(note.body());
            if (sanitized.isBlank()) {
                continue;
            }
            Integer endLine = note.endLine();
            boolean isMultiLine = endLine != null && endLine > note.startLine();
            FeedbackAnchor.DiffAnchor anchor = isMultiLine
                    ? FeedbackAnchor.DiffAnchor.range(
                            note.filePath(), note.startLine(), Objects.requireNonNull(endLine))
                    : FeedbackAnchor.DiffAnchor.singleLine(note.filePath(), note.startLine());
            observations.add(new InlineFeedbackChannel.InlineFeedback(
                    anchor,
                    packageId == null ? commentFormatter.appendInlineFeedbackPrompt(sanitized) : sanitized,
                    packageId == null ? HEPHAESTUS_MARKER : "<!-- hephaestus-approved-package:" + packageId + " -->",
                    packageId == null ? note.deliveryKey() : "approved:" + packageId + ":" + index));
        }
        return observations;
    }

    record DiffNoteResult(
            int posted,
            int failed,
            List<InlineFeedbackChannel.DeliveredSignal> signals,
            boolean suppressed,
            List<String> suppressedDeliveryKeys) {
        DiffNoteResult(int posted, int failed, List<InlineFeedbackChannel.DeliveredSignal> signals) {
            this(posted, failed, signals, false, List.of());
        }
    }
}
