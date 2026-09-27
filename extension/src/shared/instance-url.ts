/**
 * The Hephaestus instance a user connects to is an origin, nothing more: HTTPS always, and plain
 * HTTP only for a loopback server in a development build. A path, query or credentials in what was
 * typed are dropped or refused rather than carried into every request.
 */
export type InstanceOriginResult =
	| { ok: true; origin: string }
	| { ok: false; reason: "invalid" | "insecure" | "credentials" };

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

export function isLoopbackHost(hostname: string): boolean {
	return LOOPBACK_HOSTS.has(hostname);
}

export function parseInstanceOrigin(
	input: string,
	options: { allowLoopbackHttp: boolean },
): InstanceOriginResult {
	const trimmed = input.trim();
	if (trimmed === "") {
		return { ok: false, reason: "invalid" };
	}
	const withScheme = /^[a-z][a-z0-9+.-]*:\/\//iu.test(trimmed) ? trimmed : `https://${trimmed}`;
	let url: URL;
	try {
		url = new URL(withScheme);
	} catch {
		return { ok: false, reason: "invalid" };
	}
	if (url.username !== "" || url.password !== "") {
		return { ok: false, reason: "credentials" };
	}
	if (url.hostname === "") {
		return { ok: false, reason: "invalid" };
	}
	if (url.protocol === "https:") {
		return { ok: true, origin: url.origin };
	}
	if (url.protocol === "http:" && options.allowLoopbackHttp && isLoopbackHost(url.hostname)) {
		return { ok: true, origin: url.origin };
	}
	return { ok: false, reason: url.protocol === "http:" ? "insecure" : "invalid" };
}

/** The match pattern Chrome's permission APIs take for one origin. */
export function originPattern(origin: string): string {
	const url = new URL(origin);
	// A match pattern has no port: `http://localhost/*` covers every port on that host.
	return `${url.protocol}//${url.hostname}/*`;
}

/** The origin a match pattern names, or `undefined` for a wildcard host. */
export function patternOrigin(pattern: string): string | undefined {
	const match = /^(?<scheme>https?):\/\/(?<host>[^/*]+)\/\*$/u.exec(pattern);
	if (match?.groups === undefined) {
		return undefined;
	}
	return `${match.groups.scheme}://${match.groups.host}`;
}

export const INSTANCE_ORIGIN_MESSAGES: Record<
	Exclude<InstanceOriginResult, { ok: true }>["reason"],
	string
> = {
	invalid: "Enter the address of a Hephaestus instance, such as https://hephaestus.example.com.",
	insecure: "The address must start with https://.",
	credentials: "Leave the user name and password out of the address.",
};
