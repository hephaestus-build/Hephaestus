package de.tum.cit.aet.hephaestus.mentor.spi;

import java.util.UUID;

/** The typed input a transport renders from: fixed text, or an answer with its basis. */
public sealed interface RenderProjection {
    /** A fixed line of the kind's registry copy, with no source-derived text. */
    record FixedText(EffectKind kind) implements RenderProjection {}

    /** The answer entry, its basis, and whether the fixed app continuation goes in the same post. */
    record Answer(UUID answerEntry, BasisRef basis, boolean appContinuation) implements RenderProjection {}
}
