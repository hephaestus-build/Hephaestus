package de.tum.cit.aet.hephaestus.core.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Text that a client can render from a message catalog")
public record LabelDTO(
        @Schema(description = "Extensible enum: a catalog key. Accept keys that the client does not know.") @NonNull
        String code,

        @Nullable Map<String, LabelParamDTO> params,

        @Schema(description = "English fallback text. Use it when the client has no copy for this key.") @NonNull
        String text) {

    @Schema(name = "LabelParamDTO", description = "One named value for a label template")
    public record LabelParamDTO(
            @Schema(description = "Extensible enum: value, instant, count or ownWords. Accept other kinds.") @NonNull
            String type,

            @Nullable String value,
            @Nullable Instant instant,
            // This slot carries work counts. People counts must pass through SmallCounts.
            @Nullable Integer count,
            @Nullable String ownWords) {}
}
