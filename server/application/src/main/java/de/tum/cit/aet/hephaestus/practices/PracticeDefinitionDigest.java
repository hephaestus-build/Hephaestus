package de.tum.cit.aet.hephaestus.practices;

final class PracticeDefinitionDigest {

    private PracticeDefinitionDigest() {}

    static String digest(String slug, PracticeDefinition definition) {
        CanonicalDigest digest = new CanonicalDigest().add(slug).add(definition.name());
        ReviewRuleFingerprint.addOccasion(
                digest,
                definition.signals(),
                definition.evidenceRequirements(),
                definition.onDrafts(),
                definition.subject(),
                definition.precondition());
        digest.add(definition.criteria())
                .addNullable(definition.precomputeScript())
                .add(PracticeAutomatedReviewPolicyDigest.digest(definition.automatedReviewPolicy()))
                .addNullable(definition.whyItMatters())
                .addNullable(definition.whatGoodLooksLike())
                .addNullable(definition.groupSlug());
        if (!definition.deliveryBehavior().equals(PracticeDeliveryBehavior.DEFAULT)) {
            digest.add("deliveryBehavior")
                    .add(String.valueOf(definition.deliveryBehavior().summaryOnly()))
                    .addNullable(definition.deliveryBehavior().overlapGroup())
                    .addNullable(definition.deliveryBehavior().redundantToSlug());
        }
        return digest.hex();
    }
}
