package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
public class ScmSummaryPoster {
    private final List<SummaryWriter> writers;

    public ScmSummaryPoster(List<SummaryWriter> writers) {
        this.writers = List.copyOf(writers);
    }

    public SummaryHandle post(FeedbackTarget target, SummaryContent content, CopyWriteFence fence) {
        SummaryWriter writer = writer(target);
        if (!fence.beforeCreate(List.of(content.copyId()), List.<CopyReceipt>of()))
            throw new FeedbackNotSentException("Feedback write was not admitted");
        return writer.postSummary(target, content);
    }

    public CopyUpdateReceipt update(FeedbackTarget target, SummaryHandle existing, SummaryContent content) {
        return writer(target).updateSummary(target, existing, content);
    }

    private SummaryWriter writer(FeedbackTarget target) {
        var matches = writers.stream()
                .filter(w -> w.kind() == target.integration().kind())
                .toList();
        if (matches.size() != 1) throw new FeedbackNotSentException("Summary writer is unavailable or ambiguous");
        return matches.getFirst();
    }
}
