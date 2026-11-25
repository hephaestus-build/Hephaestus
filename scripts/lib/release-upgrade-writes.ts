import { createHash } from "node:crypto";

import { asArray, asRecord, asString, parseJson } from "./json.ts";

/**
 * The provider hosts that the upgrade drill answers with its recorder (`release-upgrade-recorder.ts`). Each release in
 * the drill resolves them to the recorder and trusts the recorder's CA, so no call leaves the drill's network.
 * A conversion seed that writes a connection uses one of these hosts: `outline.upgrade.test` stands for an Outline
 * server, which has no public default.
 */
export const PROVIDER_HOSTS = [
	"api.github.com",
	"github.com",
	"uploads.github.com",
	"gitlab.com",
	"slack.com",
	"hooks.slack.com",
	"outline.upgrade.test",
] as const;

/** The header that labels a call the drill sends itself, in place of the caller's address. */
export const SOURCE_HEADER = "x-upgrade-drill-source";
/** The label of the drill's own planted double write, which proves that the check catches one. */
export const PLANTED_SOURCE = "planted";

/** One request that reached the recorder. */
export interface ProviderCall {
	/** Increases by one for each call, from 1. */
	sequence: number;
	/** The caller's address on the drill's network, or the label in `SOURCE_HEADER`. */
	source: string;
	method: string;
	host: string;
	/** The path and the query. */
	path: string;
	body: string;
}

/** The calls in the recorder's answer to `GET /calls`. */
export function parseCalls(text: string): ProviderCall[] {
	return asArray(parseJson(text), "recorded calls").map((value, index) => {
		const label = `recorded call ${index}`;
		const fields = asRecord(value, label);
		const { sequence } = fields;
		if (typeof sequence !== "number" || !Number.isInteger(sequence)) {
			throw new TypeError(`${label} has no sequence`);
		}
		return {
			sequence,
			source: asString(fields.source, `${label} source`),
			method: asString(fields.method, `${label} method`),
			host: asString(fields.host, `${label} host`),
			path: asString(fields.path, `${label} path`),
			body: asString(fields.body, `${label} body`),
		};
	});
}

/** A call that the drill assigned to the database of the release that sent it. */
export interface AttributedCall {
	database: string;
	call: ProviderCall;
}

const READ_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
// Slack's Web API and Outline's API post every method, reads too. The last segment of the method names a read.
const RPC_READ =
	/^(?:test|info|list|history|replies|members|conversations|search|export|lookupByEmail|get[A-Z]\w*|access|exchange)$/u;
// A credential exchange answers with a token. It changes nothing that a person sees, and a run repeats it.
const CREDENTIAL_EXCHANGES = [
	/^\/app\/installations\/\d+\/access_tokens$/u,
	/^\/login\/oauth\/access_token$/u,
	/^\/oauth\/token$/u,
];
const LEADING_COMMENTS = /^(?:\s|#[^\n]*\n?)*/u;

function rpcMethod(host: string, path: string): string | undefined {
	if (host === "slack.com" || host === "outline.upgrade.test") {
		return /^\/api\/(?<method>[\w.]+)$/u.exec(path)?.groups?.method;
	}
	return undefined;
}

function isGraphQlQuery(body: string): boolean {
	try {
		const parsed: unknown = JSON.parse(body);
		if (typeof parsed !== "object" || parsed === null || !("query" in parsed)) {
			return false;
		}
		const { query } = parsed;
		return typeof query === "string" && !query.replace(LEADING_COMMENTS, "").startsWith("mutation");
	} catch {
		// A body that is not a GraphQL request counts as a write: the check fails closed.
		return false;
	}
}

/** What identifies a write, or `undefined` for a read or a credential exchange. */
export function writeKey(call: ProviderCall): string | undefined {
	if (READ_METHODS.has(call.method)) {
		return undefined;
	}
	const path = call.path.split("?", 1)[0] ?? "";
	if (CREDENTIAL_EXCHANGES.some((pattern) => pattern.test(path))) {
		return undefined;
	}
	if (path.endsWith("/graphql") && isGraphQlQuery(call.body)) {
		return undefined;
	}
	const rpc = rpcMethod(call.host, path);
	if (rpc !== undefined && RPC_READ.test(rpc.slice(rpc.lastIndexOf(".") + 1))) {
		return undefined;
	}
	const digest = createHash("sha256").update(call.body).digest("hex").slice(0, 16);
	return `${call.method} ${call.host}${call.path} body=${digest}`;
}

/** Assigns each call to the database of the live release whose address sent it, or to none. */
export function attribute(
	calls: readonly ProviderCall[],
	live: ReadonlyMap<string, string>,
): { attributed: AttributedCall[]; unknown: ProviderCall[] } {
	const attributed: AttributedCall[] = [];
	const unknown: ProviderCall[] = [];
	for (const call of calls) {
		const database = call.source === PLANTED_SOURCE ? PLANTED_SOURCE : live.get(call.source);
		if (database === undefined) {
			unknown.push(call);
		} else {
			attributed.push({ database, call });
		}
	}
	return { attributed, unknown };
}

/**
 * Each write that reached one database's providers more than once. A copy of the seeded database is its own
 * installation, so the same write from two databases is no repeat.
 */
export function repeatedWrites(calls: readonly AttributedCall[]): string[] {
	const seen = new Map<string, number>();
	for (const { database, call } of calls) {
		const key = writeKey(call);
		if (key !== undefined) {
			const scoped = `${database}: ${key}`;
			seen.set(scoped, (seen.get(scoped) ?? 0) + 1);
		}
	}
	return [...seen]
		.filter(([, times]) => times > 1)
		.map(([write, times]) => `${write} (${times} times)`);
}
