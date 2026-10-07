package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

class InAppChannelProducerArchTest extends HephaestusArchitectureTest {

    private static final String PRODUCER_PACKAGE = "..agent.handler.inapp..";
    private static final String READER_PACKAGE = "..practices.feedback.inapp..";
    private static final String OPERATOR_WITHHOLDER =
            "de.tum.cit.aet.hephaestus.practices.reviewoutput.ReviewFeedbackQueryService";
    private static final String HISTORY_READER =
            "de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource";

    private static final String COMPOSITION_PACKAGE = "..agent.handler.composition..";

    @Test
    void onlyApprovedPackagesAccessPrivateInAppFeedback() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage(PRODUCER_PACKAGE)
                .and()
                .resideOutsideOfPackage(READER_PACKAGE)
                .and()
                .resideOutsideOfPackage(COMPOSITION_PACKAGE)
                .and()
                .haveNameNotMatching(FeedbackChannel.class.getName() + "(\\$.*)?")
                .and()
                .haveNameNotMatching(ObservationOrigin.class.getName() + "(\\$.*)?")
                .and()
                .haveNameNotMatching(OPERATOR_WITHHOLDER + "(\\$.*)?")
                .and()
                .haveNameNotMatching(HISTORY_READER + "(\\$.*)?")
                .should()
                .accessField(FeedbackChannel.class, "IN_APP")
                .because("private IN_APP feedback must stay inside its approved producers and readers");
        rule.check(classes);
    }

    @Test
    void compositionCannotAccessFeedbackStorage() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(COMPOSITION_PACKAGE)
                .should()
                .dependOnClassesThat()
                .areAssignableTo(Feedback.class)
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(Repository.class)
                .because("composition may select a lane but must not read or persist feedback");
        rule.check(classes);
    }
}
