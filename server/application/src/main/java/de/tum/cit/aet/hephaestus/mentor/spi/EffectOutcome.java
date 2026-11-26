package de.tum.cit.aet.hephaestus.mentor.spi;

/** How a transport ended an effect. A retry is the outbox's own next attempt, never an outcome. */
public sealed interface EffectOutcome {
    record Delivered(String providerRef) implements EffectOutcome {}

    record Refused(String reason) implements EffectOutcome {}

    record Unconfirmed() implements EffectOutcome {}

    record Expired() implements EffectOutcome {}
}
