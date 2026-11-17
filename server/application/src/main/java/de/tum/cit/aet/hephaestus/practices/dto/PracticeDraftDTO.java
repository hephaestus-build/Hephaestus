package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A practice folder saved to be tried (ADR 0118 decision 7): a new draft practice, a new version of a draft, or a
 * version of a practice in use that is not current.
 *
 * @param current whether the version is the practice's current one. A version tried beside a practice in use is not
 * @param warnings what the save changed in the folder, such as a group that the workspace lacks
 */
public record PracticeDraftDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String slug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String kind,
        @Nullable String groupSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PracticeState state,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
        String versionId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int versionNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean current,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PracticeFileProblemDTO> warnings) {}
