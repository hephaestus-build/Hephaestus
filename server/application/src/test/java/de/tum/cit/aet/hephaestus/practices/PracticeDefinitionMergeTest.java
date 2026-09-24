package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PracticeDefinitionMergeTest extends BaseUnitTest {

    private static final PracticeDefinition BASE = new PracticeDefinition(
            "Original",
            PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST),
            "Original criteria",
            null,
            PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
            "Original rationale",
            null,
            null);

    @Test
    void reportsSameFieldEditsAsConflictsEvenWhenValuesMatch() {
        PracticeDefinition current = change("Shared name", "Local criteria", "Local rationale");
        PracticeDefinition offered = change("Shared name", "Offered criteria", "Original rationale");

        assertThat(PracticeDefinitionMerge.changes(BASE, current, offered))
                .containsExactly(
                        new PracticeDefinitionMerge.FieldChange(PracticeDefinitionField.NAME, true, true),
                        new PracticeDefinitionMerge.FieldChange(PracticeDefinitionField.CRITERIA, true, true),
                        new PracticeDefinitionMerge.FieldChange(PracticeDefinitionField.WHY_IT_MATTERS, false, false));
    }

    @Test
    void appliesOnlySelectedOfferFieldsAndKeepsUnchangedLocalWork() {
        PracticeDefinition current = change("Local name", "Local criteria", "Local rationale");
        PracticeDefinition offered = change("Offered name", "Original criteria", "Original rationale");

        PracticeDefinition result = PracticeDefinitionMerge.apply(
                BASE, current, offered, Map.of(PracticeDefinitionField.NAME, PracticeReleaseChoice.OFFERED));

        assertThat(result.name()).isEqualTo("Offered name");
        assertThat(result.criteria()).isEqualTo("Local criteria");
        assertThat(result.whyItMatters()).isEqualTo("Local rationale");
    }

    @Test
    void requiresExactlyOneChoiceForEachChangedOfferField() {
        PracticeDefinition offered = change("Offered name", "Offered criteria", "Original rationale");

        assertThatThrownBy(() -> PracticeDefinitionMerge.apply(
                        BASE, BASE, offered, Map.of(PracticeDefinitionField.NAME, PracticeReleaseChoice.OFFERED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PracticeDefinitionMerge.apply(
                        BASE,
                        BASE,
                        offered,
                        Map.of(
                                PracticeDefinitionField.NAME, PracticeReleaseChoice.OFFERED,
                                PracticeDefinitionField.CRITERIA, PracticeReleaseChoice.CURRENT,
                                PracticeDefinitionField.BINDINGS, PracticeReleaseChoice.OFFERED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void includesDeclaredDeliveryBehaviorInComparisonAndMerge() {
        PracticeDefinition offered = new PracticeDefinition(
                BASE.name(),
                BASE.bindings(),
                BASE.criteria(),
                BASE.precomputeScript(),
                BASE.automatedReviewPolicy(),
                BASE.whyItMatters(),
                BASE.whatGoodLooksLike(),
                BASE.groupSlug(),
                new PracticeDeliveryBehavior(true, "related", null));

        assertThat(PracticeDefinitionMerge.changes(BASE, BASE, offered))
                .containsExactly(new PracticeDefinitionMerge.FieldChange(
                        PracticeDefinitionField.DELIVERY_BEHAVIOR, true, false));
        assertThat(PracticeDefinitionMerge.apply(
                                BASE,
                                BASE,
                                offered,
                                Map.of(PracticeDefinitionField.DELIVERY_BEHAVIOR, PracticeReleaseChoice.OFFERED))
                        .deliveryBehavior())
                .isEqualTo(offered.deliveryBehavior());
    }

    private static PracticeDefinition change(String name, String criteria, String rationale) {
        return new PracticeDefinition(
                name,
                BASE.bindings(),
                criteria,
                BASE.precomputeScript(),
                BASE.automatedReviewPolicy(),
                rationale,
                BASE.whatGoodLooksLike(),
                BASE.groupSlug());
    }
}
