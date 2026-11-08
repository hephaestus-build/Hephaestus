import { setTimeout as sleep } from "node:timers/promises";

export interface ArtifactPolicy {
	timeoutMs?: number;
	attempts?: number;
	delayMs?: number;
}

export interface ArtifactRequest {
	method?: "GET" | "HEAD" | "POST" | "PUT";
	accept?: string;
	contentType?: string;
	body?: Uint8Array;
	credentials?: { header: string; origins: readonly string[] };
	acceptedStatuses?: readonly number[];
	redirects?: boolean;
}

export class ArtifactHttpError extends Error {
	readonly status: number;
	constructor(status: number, attempts: number) {
		super(`Artifact request returned HTTP ${status} after ${attempts} attempt(s)`);
		this.name = "ArtifactHttpError";
		this.status = status;
	}
}

export function artifactFailure(error: unknown): string {
	return error instanceof ArtifactHttpError
		? `HTTP ${String(error.status)}`
		: "metadata, transport or filesystem error";
}

function transportFailure(error: unknown): boolean {
	return (
		error instanceof TypeError ||
		(error instanceof DOMException && ["AbortError", "TimeoutError"].includes(error.name))
	);
}

function boundedPolicy(policy: ArtifactPolicy) {
	const attempts = policy.attempts ?? 3;
	const timeoutMs = policy.timeoutMs ?? 30_000;
	const delayMs = policy.delayMs ?? 1000;
	if (
		!Number.isSafeInteger(attempts) ||
		attempts < 1 ||
		attempts > 6 ||
		!Number.isSafeInteger(timeoutMs) ||
		timeoutMs < 1 ||
		timeoutMs > 120_000 ||
		!Number.isSafeInteger(delayMs) ||
		delayMs < 0 ||
		delayMs > 5000
	)
		throw new Error("Invalid bounded artifact request policy");
	return { attempts, timeoutMs, delayMs };
}

async function redirectedRequest(
	url: string,
	options: ArtifactRequest,
	signal: AbortSignal,
): Promise<Response> {
	const initial = new URL(url);
	// Loopback HTTP serves checksum-pinned fixtures; credentials still require an exact HTTPS origin.
	const loopback =
		initial.protocol === "http:" && ["127.0.0.1", "localhost", "[::1]"].includes(initial.hostname);
	if ((initial.protocol !== "https:" && !loopback) || initial.username || initial.password)
		throw new Error("Unsafe artifact URL");
	const method = options.method ?? "GET";
	const headers: Record<string, string> = { Accept: options.accept ?? "application/octet-stream" };
	if (options.contentType !== undefined) headers["Content-Type"] = options.contentType;
	if (options.credentials !== undefined) {
		if (!options.credentials.origins.includes(new URL(url).origin))
			throw new Error("Artifact credentials do not belong to this origin");
		headers.Authorization = options.credentials.header;
	}
	let response = await fetch(url, {
		method,
		headers,
		signal,
		redirect: "manual",
		body: options.body === undefined ? undefined : new Uint8Array(options.body).buffer,
	});
	let current = url;
	let authenticated = options.credentials !== undefined;
	for (let redirects = 0; [301, 302, 303, 307, 308].includes(response.status); redirects += 1) {
		const location = response.headers.get("location");
		await response.body?.cancel();
		if (
			options.redirects === false ||
			redirects === 5 ||
			location === null ||
			(method !== "GET" && method !== "HEAD")
		)
			throw new Error("Invalid artifact redirect");
		let target: URL;
		try {
			target = new URL(location, current);
		} catch {
			throw new Error("Invalid artifact redirect URL");
		}
		if (target.protocol !== "https:" || target.username || target.password)
			throw new Error("Unsafe artifact redirect");
		authenticated &&= options.credentials?.origins.includes(target.origin) === true;
		const redirectedHeaders: Record<string, string> = {
			Accept: options.accept ?? "application/octet-stream",
		};
		if (authenticated && options.credentials !== undefined)
			redirectedHeaders.Authorization = options.credentials.header;
		current = target.href;
		response = await fetch(target, {
			method,
			headers: redirectedHeaders,
			signal,
			redirect: "manual",
		});
	}
	return response;
}

/** The deadline covers headers, redirects and the complete body. No response body enters an error. */
export async function requestArtifact(
	url: string,
	options: ArtifactRequest = {},
	policy: ArtifactPolicy = {},
) {
	const { attempts, timeoutMs, delayMs } = boundedPolicy(policy);
	for (let attempt = 1; ; attempt += 1) {
		try {
			const response = await redirectedRequest(url, options, AbortSignal.timeout(timeoutMs));
			if (response.ok || options.acceptedStatuses?.includes(response.status) === true)
				return {
					bytes: new Uint8Array(await response.arrayBuffer()),
					headers: response.headers,
					status: response.status,
				};
			await response.body?.cancel();
			if (![408, 429, 500, 502, 503, 504].includes(response.status) || attempt === attempts)
				throw new ArtifactHttpError(response.status, attempt);
		} catch (error) {
			if (!transportFailure(error) || attempt === attempts) throw error;
		}
		console.warn(`Artifact request unavailable; retrying (${attempt + 1}/${attempts}).`);
		await sleep(attempt * delayMs);
	}
}
