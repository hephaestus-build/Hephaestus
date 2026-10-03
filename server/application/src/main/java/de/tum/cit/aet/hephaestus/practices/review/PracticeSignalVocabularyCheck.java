package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Refuses to start an application server that offers a practice signal nothing can raise. Second line
 * of defence behind {@code PracticeSignalCoverageIntegrationTest}, for images built outside that gate.
 *
 * <p>Its own gated bean rather than a {@code @PostConstruct} on {@link PracticeSignalCoverage}: that
 * class is an ungated {@code @Service} because the trace views need it in every role, and a throw from
 * its construction would crash-loop the webhook pod, whose missed push events cannot be redelivered.
 * Same shape and reason as {@code IntegrationFrameworkBootstrap}.
 */
@Component
@ConditionalOnServerRole
public class PracticeSignalVocabularyCheck {

    private final PracticeSignalCoverage coverage;

    public PracticeSignalVocabularyCheck(PracticeSignalCoverage coverage) {
        this.coverage = coverage;
    }

    @PostConstruct
    void verify() {
        coverage.validateAuthoringVocabulary();
    }
}
