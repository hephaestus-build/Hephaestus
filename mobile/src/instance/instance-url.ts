/** A Hephaestus this app can sign in to: where its API answers, and how to name it to a person. */
export interface Instance {
	apiBaseUrl: string;
	label: string;
	/** The web app of this Hephaestus, as it says itself: privacy notice, settings, export live there. */
	webUrl: string;
}

const LOOPBACK = new Set(["localhost", "127.0.0.1", "[::1]"]);

/**
 * The web app address a server reports, checked before the app ever opens it: HTTPS (plain HTTP only
 * in a development build), no credentials, no query or fragment. A development server configured for
 * `localhost` names the host machine as the browser there sees it; a simulator, emulator or phone
 * reaches that machine at the host it reached the API on, so the host is swapped and the port kept.
 */
export function webAddress(
	reported: string,
	apiBaseUrl: string,
	allowInsecure: boolean,
): string | undefined {
	let url: URL;
	try {
		url = new URL(reported);
	} catch {
		return undefined;
	}
	const secure = url.protocol === "https:";
	if (
		!(secure || (allowInsecure && url.protocol === "http:")) ||
		url.username !== "" ||
		url.password !== "" ||
		url.search !== "" ||
		url.hash !== ""
	) {
		return undefined;
	}
	if (allowInsecure && LOOPBACK.has(url.hostname)) {
		url.hostname = new URL(apiBaseUrl).hostname;
	}
	return url.toString().replace(/\/+$/u, "");
}

/**
 * Where a person's Hephaestus answers, from whatever they typed: a bare host, the address they open in
 * a browser, or the API itself. A standard deployment serves the API under `/api` next to the web app;
 * a local server serves it at the root, so both are candidates, `/api` first.
 */
export type InstanceUrl =
	| { ok: true; candidates: string[]; label: string }
	| { ok: false; reason: "empty" | "invalid" | "insecure" };

export function instanceUrlCandidates(input: string, allowInsecure: boolean): InstanceUrl {
	const trimmed = input.trim();
	if (trimmed === "") {
		return { ok: false, reason: "empty" };
	}
	const withScheme = /^[a-z][a-z0-9+.-]*:\/\//iu.test(trimmed) ? trimmed : `https://${trimmed}`;
	let url: URL;
	try {
		url = new URL(withScheme);
	} catch {
		return { ok: false, reason: "invalid" };
	}
	if (url.protocol !== "https:" && url.protocol !== "http:") {
		return { ok: false, reason: "invalid" };
	}
	if (url.hostname === "" || url.username !== "" || url.password !== "") {
		return { ok: false, reason: "invalid" };
	}
	if (url.protocol === "http:" && !allowInsecure) {
		return { ok: false, reason: "insecure" };
	}
	const path = url.pathname.replace(/\/+$/u, "");
	const base = `${url.protocol}//${url.host}${path}`;
	const candidates = path.endsWith("/api") ? [base] : [`${base}/api`, base];
	return { ok: true, candidates, label: url.host };
}

/** Whether `current` is older than `minimum`, both `major.minor.patch`; blank or unreadable means no minimum. */
export function isOlderThan(current: string, minimum: string): boolean {
	const have = parseVersion(current);
	const need = parseVersion(minimum);
	if (have === undefined || need === undefined) {
		return false;
	}
	const difference = have.findIndex((part, index) => part !== need[index]);
	return difference !== -1 && (have[difference] ?? 0) < (need[difference] ?? 0);
}

function parseVersion(version: string): number[] | undefined {
	const parts = /^(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)/u.exec(version.trim())?.groups;
	return parts === undefined
		? undefined
		: [Number(parts.major), Number(parts.minor), Number(parts.patch)];
}
