package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountFeedbackExport;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackExportRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Feedback delivered to the account, in the states its developer may learn of, with what was posted of it. Withdrawn
 * feedback leaves the app as text, so it leaves the export as text too: its dates stay.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class FeedbackExportAdapter implements AccountFeedbackExport {
    private final FeedbackExportRepository feedback;

    @Override
    public List<Received> received(long accountId) {
        var received = feedback.findReceived(
                accountId,
                FeedbackState.VISIBLE_TO_DEVELOPER.stream().map(Enum::name).toList());
        var shown = received.stream()
                .filter(row -> row.getWithdrawnAt() == null)
                .map(FeedbackExportRepository.Received::getFeedbackId)
                .toList();
        var pieces = shown.isEmpty()
                ? Map.<UUID, List<Piece>>of()
                : feedback.findPostedPieces(accountId, shown).stream()
                        .collect(Collectors.groupingBy(
                                FeedbackExportRepository.Piece::getFeedbackId,
                                Collectors.mapping(
                                        p -> new Piece(
                                                p.getKind(),
                                                p.getPath(),
                                                p.getStartLine(),
                                                p.getEndLine(),
                                                p.getBody(),
                                                p.getPostedAt()),
                                        Collectors.toList())));
        return received.stream()
                .map(row -> {
                    boolean withdrawn = row.getWithdrawnAt() != null;
                    return new Received(
                            row.getWorkspace(),
                            row.getFeedbackId(),
                            row.getDestination(),
                            withdrawn ? null : row.getBody(),
                            pieces.getOrDefault(row.getFeedbackId(), List.of()),
                            row.getCreatedAt(),
                            row.getDeliveredAt(),
                            row.getWithdrawnAt());
                })
                .toList();
    }

    @Override
    public List<Response> responses(long accountId) {
        return feedback.findResponses(accountId).stream()
                .map(r -> new Response(
                        r.getWorkspace(),
                        r.getFeedbackId(),
                        r.getResolution(),
                        r.getUsefulness(),
                        r.getComment(),
                        r.getCreatedAt()))
                .toList();
    }
}
