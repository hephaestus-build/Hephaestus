package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Map;

/** Per-kind webhook signature verification. */
public interface WebhookSignatureVerifier {
    IntegrationKind kind();

    VerificationResult verify(WebhookRequest request);

    /** Body + headers. Workspace context is resolved by the verifier itself when needed. */
    @SuppressWarnings("ArrayRecordComponent") // raw webhook body; never compared, hashed or printed
    record WebhookRequest(byte[] body, Map<String, String> headers) {}

    sealed interface VerificationResult
            permits VerificationResult.Verified,
                    VerificationResult.Invalid,
                    VerificationResult.MissingSignature,
                    VerificationResult.StaleTimestamp,
                    VerificationResult.RespondImmediately {
        record Verified() implements VerificationResult {}

        /**
         * Authentication failed. {@code reason} is for server-side logs only — the pipeline
         * MUST NOT echo it in the HTTP response (side-channel for attacker probing).
         */
        record Invalid(String reason) implements VerificationResult {}

        record MissingSignature() implements VerificationResult {}

        /** Replay protection — request timestamp drifted beyond the allowed window. */
        record StaleTimestamp(long driftSeconds) implements VerificationResult {}

        /**
         * Pipeline must respond directly with the given payload — request must NOT be
         * published to NATS. Used for Slack {@code url_verification} and Asana's
         * X-Hook-Secret echo.
         */
        @SuppressWarnings("ArrayRecordComponent") // response bytes written verbatim; never compared, hashed or printed
        record RespondImmediately(int statusCode, String contentType, byte[] body, Map<String, String> headers)
                implements VerificationResult {
            public RespondImmediately(int statusCode, String contentType, byte[] body) {
                this(statusCode, contentType, body, Map.of());
            }
        }
    }
}
