package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Runtime-owned model readiness, without exposing catalog or credential internals to onboarding. */
public interface WorkspaceAiAvailability {
    List<Option> options(long workspaceId);

    /** {@code models} are the ones that would serve this answer today, practice reviews first. */
    record Option(MemberAiChoice choice, boolean practiceReviewsReady, boolean mentorReady, List<Model> models) {}

    /** A model a developer may see by name, with its declared brand when known. */
    record Model(String name, @Nullable AiModelBrand brand) {}
}
