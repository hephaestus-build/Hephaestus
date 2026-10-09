package de.tum.cit.aet.hephaestus.practices;

final class PracticeDefinitionDigest {

    private PracticeDefinitionDigest() {}

    static String digest(String slug, PracticeDefinition definition) {
        CanonicalDigest digest = new CanonicalDigest().add(slug).add(definition.name());
        digest.addInt(definition.signals().size());
        definition.signals().forEach(signal -> digest.add(signal.value()));
        digest.addInt(definition.reviewWhen().size());
        definition.reviewWhen().forEach((dimension, values) -> {
            digest.add(dimension).addInt(values.size());
            values.forEach(digest::add);
        });
        ReviewRuleFingerprint.addAssessment(
                digest, definition.evidenceRequirements(), definition.subject(), definition.precondition());
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
        // Absent guidance adds nothing, so a definition without a visual or guide matches the digest stored for it.
        PracticeVisual visual = definition.visual();
        if (visual != null) {
            digest.add("visual").add(visual.digest());
        }
        PracticeGuide guide = definition.guide();
        if (guide != null) {
            digest.add("guide").add(guide.digest());
        }
        return digest.hex();
    }
}
