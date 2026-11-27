package de.tum.cit.aet.hephaestus.mentor.spi;

/** The current proof of an effect. */
public sealed interface Proof {
    record Allowed(EffectIntent intent, RenderProjection render) implements Proof {}

    record Refused(ProofRefusal reason) implements Proof {}
}
