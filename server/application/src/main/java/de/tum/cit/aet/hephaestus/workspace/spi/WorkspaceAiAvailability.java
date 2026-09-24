package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Runtime-owned model readiness, without exposing catalog or credential internals to onboarding. */
public interface WorkspaceAiAvailability {
    List<Option> options(long workspaceId);

    /** {@code models} are the ones that would serve this answer today, practice reviews first. */
    record Option(MemberAiChoice choice, boolean practiceReviewsReady, boolean mentorReady, List<Model> models) {}

    /** Visible model facts; platform and operator are independent declarations. */
    record Model(
            String name,
            @Nullable AiModelBrand brand,
            @Nullable LlmConnectionPlatform connectionPlatform,
            DataHandlingTier dataHandlingTier) {}
}
