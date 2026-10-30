package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import org.jspecify.annotations.NonNull;

/** A private staging receipt; staging creates no visible result or feedback. */
public record StageResponseDTO(
        @NonNull String practiceSlug, @NonNull String digest) {}
