/**
 * The packaged server serves the web app and its API on one origin (ADR 0100), so the browser suite tests the
 * artifact that ships. The session cookie is SameSite=Lax and host-only, which one origin satisfies by construction.
 */
export const E2E_BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:8080";
/** The server serves its API under `/api`. */
export const E2E_API_URL = `${E2E_BASE_URL}/api`;
