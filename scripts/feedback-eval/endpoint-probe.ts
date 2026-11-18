/**
 * Checks that the CI model endpoint answers before a run composes anything: docs/contributor/ci-cd.mdx § CI model
 * connection. A composer pays a Gradle start and a 30-second connect timeout for each attempt, so a run against an
 * endpoint that is down spends hours and holds the shared endpoint lock before it fails. This probe sends one bounded
 * request, and one more after a failure. Any HTTP answer counts as reachable: the probe checks the connection, not
 * the model. It never prints the URL or the key, and it names a failure by its error code only, because a connection
 * error's message holds the host's address.
 * Usage: node scripts/feedback-eval/endpoint-probe.ts --title "<workflow name>"
 * Reads HEPHAESTUS_LIVE_LLM_BASE_URL and HEPHAESTUS_LIVE_LLM_API_KEY. Writes `reachable=<true|false>` to
 * GITHUB_OUTPUT and a line to GITHUB_STEP_SUMMARY when they are set. Exits 1 when the endpoint is unreachable.
 */
import { once } from "node:events";
import { appendFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { parseArgs } from "node:util";

export type Probe = { reachable: true; status: number } | { reachable: false; reason: string };

export interface ProbeLimits {
	/** How long the TCP and TLS connection may take. */
	connectMs: number;
	/** How long the response headers may take once connected. */
	responseMs: number;
	/** Requests in all: the first one and its retries. */
	attempts: number;
}

export const LIMITS: ProbeLimits = { connectMs: 5000, responseMs: 30_000, attempts: 2 };

/** The models list beside the chat completions that the composer calls: `<base>/v1/models`, or `<base>/models` after `/v1`. */
export function modelsUrl(base: string): URL {
	const trimmed = base.replace(/\/+$/u, "");
	return new URL(trimmed.endsWith("/v1") ? `${trimmed}/models` : `${trimmed}/v1/models`);
}

/** An error code such as `ECONNREFUSED`, or a fixed word: never an error message, which can hold an address. */
function reasonOf(error: unknown): string {
	const code =
		typeof error === "object" && error !== null && "code" in error ? error.code : undefined;
	return typeof code === "string" && /^[A-Z][A-Z0-9_]*$/u.test(code) ? code : "connection-failed";
}

/** One request: connected within `connectMs`, and its response headers within `responseMs` after that. */
export async function probeOnce(url: URL, key: string, limits: ProbeLimits): Promise<Probe> {
	const client = url.protocol === "https:" ? https : http;
	const request = client.request(url, {
		method: "GET",
		headers: { Authorization: `Bearer ${key}` },
	});
	const timeout = new AbortController();
	const socket = { connected: false };
	let status = 0;
	let timer = setTimeout(() => {
		timeout.abort();
	}, limits.connectMs);
	request.on("socket", (opened) => {
		opened.once(url.protocol === "https:" ? "secureConnect" : "connect", () => {
			socket.connected = true;
			clearTimeout(timer);
			timer = setTimeout(() => {
				timeout.abort();
			}, limits.responseMs);
		});
	});
	request.on("response", (response) => {
		status = response.statusCode ?? 0;
		response.resume();
	});
	// Destroying the request emits an error after the wait below has stopped listening; this listener keeps it handled.
	let error: unknown;
	request.on("error", (failure) => {
		error = failure;
	});
	const answered = once(request, "response", { signal: timeout.signal });
	request.end();
	try {
		await answered;
		return { reachable: true, status };
	} catch {
		if (timeout.signal.aborted) {
			return {
				reachable: false,
				reason: socket.connected ? "response-timeout" : "connect-timeout",
			};
		}
		return { reachable: false, reason: reasonOf(error) };
	} finally {
		clearTimeout(timer);
		request.destroy();
	}
}

/** Up to `attempts` requests, stopping at the first answer. The last failure names the reason. */
export async function probeEndpoint(
	base: string,
	key: string,
	limits: ProbeLimits = LIMITS,
): Promise<Probe> {
	let url: URL;
	try {
		url = modelsUrl(base);
	} catch {
		return { reachable: false, reason: "invalid-base-url" };
	}
	let probe: Probe = { reachable: false, reason: "not-tried" };
	for (let attempt = 1; attempt <= limits.attempts; attempt += 1) {
		probe = await probeOnce(url, key, limits);
		if (probe.reachable) {
			return probe;
		}
	}
	return probe;
}

if (import.meta.main) {
	const { values } = parseArgs({ options: { title: { type: "string" } } });
	const title = values.title ?? "Model connection";
	const probe = await probeEndpoint(
		process.env.HEPHAESTUS_LIVE_LLM_BASE_URL ?? "",
		process.env.HEPHAESTUS_LIVE_LLM_API_KEY ?? "",
	);
	const output = process.env.GITHUB_OUTPUT;
	const summary = process.env.GITHUB_STEP_SUMMARY;
	if (output !== undefined && output !== "") {
		appendFileSync(output, `reachable=${String(probe.reachable)}\n`);
	}
	const line = probe.reachable
		? `The model endpoint answered (HTTP ${String(probe.status)}).`
		: `The model endpoint is unreachable (${probe.reason}), so nothing was composed.`;
	if (summary !== undefined && summary !== "") {
		appendFileSync(summary, `## ${title}\n\n${line}\n`);
	}
	if (probe.reachable) {
		console.log(line);
	} else {
		console.log(`::error title=${title}::${line}`);
		process.exitCode = 1;
	}
}
