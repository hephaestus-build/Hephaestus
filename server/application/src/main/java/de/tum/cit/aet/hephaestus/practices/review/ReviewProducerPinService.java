package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackProducer;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewProducerPin;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class ReviewProducerPinService implements ReviewProducerPin {
    private final ReviewRepository reviews;

    @Override
    public Optional<FeedbackProducer> pinned(long workspaceId, UUID reviewId) {
        return reviews.findFeedbackProducer(workspaceId, reviewId).map(FeedbackProducer::valueOf);
    }

    @Override
    public void pin(long workspaceId, UUID reviewId, FeedbackProducer producer) {
        if (reviews.pinFeedbackProducer(workspaceId, reviewId, producer.name()) != 1)
            throw new IllegalStateException("The review already has its producer");
    }
}
