import environment from "@/environment";

/** The path the API is served under, without a trailing slash; empty when it is the origin root. */
export function apiBasePath(): string {
	try {
		return new URL(environment.serverUrl, window.location.origin).pathname.replace(/\/$/u, "");
	} catch {
		return "";
	}
}
