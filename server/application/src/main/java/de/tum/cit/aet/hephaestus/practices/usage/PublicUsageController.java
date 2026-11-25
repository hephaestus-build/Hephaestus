package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

// Anonymous by design: the figures are instance totals that passed the disclosure rules of ADR 0153. A line comment,
// not Javadoc: springdoc publishes a controller's Javadoc as its API tag description.
@RestController
@ConditionalOnServerRole
@RequiredArgsConstructor
@PreAuthorize("permitAll()")
public class PublicUsageController {

    /**
     * A shared cache may keep a response for 5 minutes, so turning the page off or a withdrawal applies within that
     * time. Only a 200 or a 304 carries it: an error keeps the no-store of the security headers.
     */
    static final CacheControl CACHE =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().mustRevalidate();

    private final PublicUsageService usage;
    private final JsonMapper json;
    private final Clock clock;

    @GetMapping(value = "/public/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(operationId = "getPublicUsage", summary = "Read the released half-years of Hephaestus in numbers")
    @ApiResponse(responseCode = "200", description = "Released periods, newest first")
    @ApiResponse(responseCode = "304", description = "The released periods have not changed", content = @Content)
    @ApiResponse(
            responseCode = "412",
            description = "If-Match names another version",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PublicUsageDTO> getPublicUsage(WebRequest request) {
        Instant read = clock.instant();
        return cached(usage.periods(), request, read);
    }

    @GetMapping(value = "/public/usage/{period}", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(operationId = "getPublicUsagePeriod", summary = "Read one released half-year of Hephaestus in numbers")
    @ApiResponse(responseCode = "200", description = "The released period")
    @ApiResponse(responseCode = "304", description = "The released period has not changed", content = @Content)
    @ApiResponse(
            responseCode = "412",
            description = "If-Match names another version",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PublicUsagePeriodDTO> getPublicUsagePeriod(@PathVariable String period, WebRequest request) {
        Instant read = clock.instant();
        return cached(usage.period(period), request, read);
    }

    /**
     * A strong ETag over the JSON of the body, after the withdrawals, for GET and HEAD alike. A matching
     * {@code If-None-Match} gets a 304; the switch and the withdrawals were already checked to build the body. A
     * failed {@code If-Match} gets a 412 problem, which no cache keeps.
     */
    private <T> ResponseEntity<T> cached(T body, WebRequest request, Instant read) {
        String tag = sha256(json.writeValueAsBytes(body));
        String etag = EntityTagPrecondition.format(tag);
        // Spring checks If-Match only for unsafe methods; RFC 9110 § 13.2.2 evaluates it first, for every method.
        String[] ifMatch = request.getHeaderValues(HttpHeaders.IF_MATCH);
        if (ifMatch != null
                && ifMatch.length > 0
                && !EntityTagPrecondition.parse(String.join(", ", ifMatch)).matches(tag)) {
            throw preconditionFailed();
        }
        // The response is as old as the policy read that built it, not as the moment it is sent: a cache counts its 5
        // minutes from Date, so a read that stalled across a switch-off or a withdrawal is not kept longer.
        var dated = new HttpHeaders();
        dated.setDate(read.toEpochMilli());
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .headers(dated)
                    .eTag(etag)
                    .cacheControl(CACHE)
                    .build();
        }
        // A fixed content type: the converter then writes UTF-8, the bytes that the ETag names, whatever charset Accept
        // asks.
        return ResponseEntity.ok()
                .headers(dated)
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(etag)
                .cacheControl(CACHE)
                .body(body);
    }

    /** A problem response, which no cache keeps: the security headers mark every error no-store. */
    private static ResponseStatusException preconditionFailed() {
        return new ResponseStatusException(
                HttpStatus.PRECONDITION_FAILED, "These figures changed since the version that If-Match names.");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
