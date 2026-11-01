package de.tum.cit.aet.hephaestus.agent.composer;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/** A model turn and the admitted facts its package tools may use. */
record ReviewCompositionTurn(
        String text,
        ObjectNode toolParameters,
        Map<String, Evidence.Result> restable,
        boolean lineNotes,
        Map<String, ReviewOnWork.Witness> witnesses,
        @Nullable String publicationContext) {}
