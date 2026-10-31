package de.tum.cit.aet.hephaestus.core.browserfaults;

import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a browser reports about a failure that a reader met. Every value is a closed set or a bounded
 * pattern, so a report carries no text that a caller chose.
 */
public final class BrowserFaultsDTOs {

    private BrowserFaultsDTOs() {}

    /** How the failure reached the reader, and the error types that each kind can have. */
    public enum Kind {
        /** An error that nothing caught: the window's `error` event or React's root. */
        UNCAUGHT_ERROR,
        /** A promise that failed with no handler. */
        UNHANDLED_REJECTION,
        /** An error boundary caught a render error, so the reader saw an error view instead of the page. */
        RENDER_ERROR,
        /** React recovered from an error by itself, such as by rendering a part again. */
        RECOVERED_ERROR,
        /** A script of the web app did not load, such as after a deploy removed it. */
        CHUNK_LOAD_FAILED,
        /** A request to the server failed with a 5xx status, or never reached it. */
        REQUEST_FAILED;

        /** The error types that a fault of this kind can have; any other combination is refused. */
        public Set<ErrorType> errorTypes() {
            return switch (this) {
                case CHUNK_LOAD_FAILED -> Set.of(ErrorType.CHUNK);
                case REQUEST_FAILED -> Set.of(ErrorType.HTTP_5XX, ErrorType.NETWORK);
                case UNCAUGHT_ERROR, UNHANDLED_REJECTION, RENDER_ERROR, RECOVERED_ERROR -> ErrorType.SCRIPT;
            };
        }
    }

    /** The failure's class, reduced to the cases that decide a fix. */
    public enum ErrorType {
        TYPE_ERROR,
        REFERENCE_ERROR,
        SYNTAX_ERROR,
        RANGE_ERROR,
        OTHER,
        CHUNK,
        HTTP_5XX,
        NETWORK;

        /** The classes of a script error. */
        static final Set<ErrorType> SCRIPT = Set.of(TYPE_ERROR, REFERENCE_ERROR, SYNTAX_ERROR, RANGE_ERROR, OTHER);
    }

    @Schema(description = "One frame of the stack, in a script that the server's own web app ships")
    public record FrameDTO(
            @Schema(description = "The content hash in the script's file name, which names the build")
            @NotNull
            @Pattern(regexp = "[A-Za-z0-9_-]{8}")
            String chunk,

            @NotNull @Min(1) @Max(10_000_000) Integer line,
            @NotNull @Min(1) @Max(10_000_000) Integer column) {}

    @Schema(description = "One failure that a reader met, counted while its page stayed open")
    public record BrowserFaultDTO(
            @NotNull Kind kind,
            @NotNull Page page,
            @NotNull ErrorType errorType,

            @Schema(description = "The web app's release: a version or a commit")
            @NotNull
            @Pattern(regexp = "[0-9a-f]{7,40}|\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}(-(alpha|beta|rc)\\.\\d{1,4})?|DEV")
            String release,

            @NotNull @Size(max = 10) List<@Valid @NotNull FrameDTO> frames,

            @Schema(description = "How often the same failure happened, at most 100") @NotNull @Min(1) @Max(100)
            Integer count,

            @Schema(
                    description = "For a failed request: the trace id that the server returned as X-Request-Id",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable
            @Pattern(regexp = "[0-9a-f]{32}")
            String traceId,

            @Schema(
                    description = "For a failed request: its 5xx status",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable
            @Min(500)
            @Max(599)
            Integer status) {

        /** The error type fits the kind, and only a failed request names a trace or a status. */
        @AssertTrue
        @Schema(hidden = true)
        public boolean isConsistent() {
            // Jackson can bind a missing value as null before @NotNull reports it.
            if (kind == null || errorType == null) {
                return true;
            }
            boolean request = kind == Kind.REQUEST_FAILED;
            return kind.errorTypes().contains(errorType)
                    && (request || (traceId == null && status == null))
                    && (status == null || errorType == ErrorType.HTTP_5XX);
        }
    }

    @Schema(description = "The failures that one page met since its last report")
    public record BrowserFaultsReportDTO(
            @NotNull @Size(min = 1, max = 20) List<@Valid @NotNull BrowserFaultDTO> faults) {}
}
