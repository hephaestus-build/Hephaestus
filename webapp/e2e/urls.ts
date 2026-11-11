export const E2E_PORT = process.env.E2E_PORT ?? "4200";
export const E2E_BASE_URL = process.env.E2E_BASE_URL ?? `http://localhost:${E2E_PORT}`;
export const E2E_SERVER_URL = process.env.E2E_SERVER_URL ?? "http://localhost:8080";
/** The server serves its API under `/api`. */
export const E2E_API_URL = `${E2E_SERVER_URL}/api`;

// The session cookie is SameSite=Lax: the browser sends it to the server only from the same site. localhost and
// 127.0.0.1 are different sites, so a mismatch signs every test out instead of failing where it starts.
const serverHost = new URL(E2E_SERVER_URL).hostname;
const appHost = new URL(E2E_BASE_URL).hostname;
if (serverHost !== appHost) {
	throw new Error(
		`E2E_SERVER_URL (${serverHost}) and the web app (${appHost}) must use the same host, or the browser drops the session cookie.`,
	);
}
