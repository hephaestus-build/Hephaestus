import { readFileSync } from "node:fs";

import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { AuthenticatedApi, publicApi } from "~/background/api";
import {
	type ContextEnvironment,
	resolveContext,
	resultPage,
	WorkspaceDirectory,
	workFeedback,
} from "~/background/context";
import { type SessionRecord, SessionStore } from "~/background/session";
import { PageReport } from "~/components/report/PageReport";
import oldestServer from "~/testing/oldest-server-api.json";
import { required } from "~/testing/required";

/**
 * The current extension against an older server. `oldest-server-api.json` is a tagged release's own
 * API (`scripts/extension-oldest-server-spec.ts`), and each answer holds only the fields its schema
 * requires, plus the identity below: what a new extension gets from a server that has not upgraded.
 * The file is 1.0.0-rc.1's until 1.0.0 is tagged. That release's API is the shape to hold to, although
 * the version check refuses it, because its discovery file names no version.
 */
type Schema = Record<string, unknown>;

const ISSUER = {
	origin: "https://old.example.test",
	apiBase: "https://old.example.test/api",
	webAppOrigin: "https://old.example.test",
};
const PAGE = "https://github.com/octo/app/pull/1";
const NOW = Date.parse("2026-11-03T00:00:00Z");
const MAX_DEPTH = 12;

// Enough identity for the extension's own checks to accept the record as this page's work.
const IDENTITY: Readonly<Record<string, string>> = {
	id: "item-1",
	url: PAGE,
	workspaceSlug: "acme",
	providerType: "GITHUB",
	provider: "GITHUB",
	serverUrl: "https://github.com",
};

const schemas = oldestServer.components.schemas as Record<string, Schema>;

function isSchema(value: unknown): value is Schema {
	return typeof value === "object" && value !== null;
}

function scalar(schema: Schema): unknown {
	switch (schema.type) {
		case "integer":
		case "number": {
			return 1;
		}
		case "boolean": {
			return false;
		}
		default: {
			return schema.format === "date-time" ? new Date(NOW).toISOString() : "text";
		}
	}
}

interface Fill {
	/** Whether a field that may be null is null, the leanest answer the schema allows. */
	nulls: boolean;
}

function sample(schema: Schema, fill: Fill, name = "", depth = 0): unknown {
	if (typeof schema.$ref === "string") {
		return sample(schemas[schema.$ref.split("/").at(-1) ?? ""] ?? {}, fill, name, depth);
	}
	const options = [schema.oneOf, schema.anyOf, schema.allOf].find(Array.isArray);
	if (options !== undefined) {
		return sampleOptions(schema, options.filter(isSchema), fill, name, depth);
	}
	const types: unknown[] = Array.isArray(schema.type) ? schema.type : [schema.type];
	// A review context without its item says the server does not know the work.
	if (fill.nulls && types.includes("null") && name !== "item") {
		return null;
	}
	const type = types.find((candidate) => candidate !== "null");
	if (name in IDENTITY) {
		return IDENTITY[name];
	}
	if (Array.isArray(schema.enum)) {
		return schema.enum.find((value) => value !== null);
	}
	if (type === "array") {
		return depth >= MAX_DEPTH || !isSchema(schema.items)
			? []
			: [sample(schema.items, fill, "", depth + 1)];
	}
	if (type === "object" || isSchema(schema.properties)) {
		return sampleObject(schema, fill, depth);
	}
	return scalar({ ...schema, type });
}

function sampleOptions(
	schema: Schema,
	options: Schema[],
	fill: Fill,
	name: string,
	depth: number,
): unknown {
	const values = options
		.filter((option) => option.type !== "null")
		.map((option) => sample(option, fill, name, depth));
	return Array.isArray(schema.allOf)
		? Object.assign({}, ...values.filter(isSchema))
		: (values[0] ?? null);
}

function sampleObject(schema: Schema, fill: Fill, depth: number): Schema {
	const properties = isSchema(schema.properties) ? schema.properties : {};
	const mandatory: unknown[] = Array.isArray(schema.required) ? schema.required : [];
	const names = new Set([
		...mandatory,
		...Object.keys(properties).filter((name) => name in IDENTITY),
	]);
	return Object.fromEntries(
		[...names].flatMap((name) => {
			const property = typeof name === "string" ? properties[name] : undefined;
			return typeof name === "string" && isSchema(property)
				? [[name, sample(property, fill, name, depth + 1)]]
				: [];
		}),
	);
}

const OPERATIONS = Object.entries(
	oldestServer.paths as Record<string, Record<string, Schema>>,
).flatMap(([path, item]) =>
	Object.entries(item).map(([method, operation]) => ({
		method: method.toUpperCase(),
		pattern: new RegExp(`/api${path.replaceAll(/\{[^}]+\}/gu, "[^/]+")}$`, "u"),
		path,
		operation,
	})),
);

function answerSchema(operation: Schema): Schema | undefined {
	const responses = isSchema(operation.responses) ? operation.responses : {};
	const ok = isSchema(responses["200"]) ? responses["200"] : {};
	const content = isSchema(ok.content) ? ok.content : {};
	const json = content["application/json"];
	return isSchema(json) && isSchema(json.schema) ? json.schema : undefined;
}

function answer(request: Request, fill: Fill): Response {
	const { pathname } = new URL(request.url);
	const known = OPERATIONS.find(
		({ method, pattern }) => method === request.method && pattern.test(pathname),
	);
	if (known === undefined) {
		return new Response(null, { status: 404 });
	}
	const schema = answerSchema(known.operation);
	return schema === undefined
		? new Response(null, { status: 204 })
		: Response.json(sample(schema, fill));
}

function environment(): ContextEnvironment<AuthenticatedApi> {
	const record: SessionRecord = {
		epoch: 1,
		generation: 1,
		credentials: {
			issuer: ISSUER,
			tokens: {
				accessToken: "access",
				accessTokenExpiresAt: new Date(NOW + 3_600_000).toISOString(),
				refreshToken: "refresh",
				sessionExpiresAt: new Date(NOW + 86_400_000).toISOString(),
			},
		},
	};
	const session = new SessionStore({
		storage: { read: async () => structuredClone(record), write: async () => undefined },
		refresh: async (issuer, token) => publicApi.refresh(issuer.apiBase, token),
		revoke: async (issuer, token) => publicApi.revoke(issuer.apiBase, token),
		now: () => NOW,
		onChange: () => undefined,
	});
	return {
		instance: ISSUER,
		api: required(AuthenticatedApi.fromSnapshot(record, session)),
		tabUrl: async () => PAGE,
		directory: new WorkspaceDirectory(),
		now: () => new Date(NOW),
	};
}

/** The operations `api.ts` imports from the generated client, each with its address there. */
function callsOfTheClient(): { name: string; address: string }[] {
	const client = readFileSync("src/background/api.ts", "utf8");
	const sdk = readFileSync("src/api/sdk.gen.ts", "utf8");
	const names = /import \{(?<names>[^}]*)\} from "~\/api\/sdk\.gen"/u.exec(client)?.groups?.names;
	return (names ?? "")
		.split(",")
		.map((name) => name.trim())
		.filter(Boolean)
		.map((name) => {
			const start = sdk.indexOf(`export const ${name} = `);
			expect(start).toBeGreaterThanOrEqual(0);
			const body = required(sdk.slice(start).split("\nexport const ")[0]);
			const method = /\.(?<method>get|post|patch|put|delete)</u.exec(body)?.groups?.method;
			const url = /url:\s*["'](?<url>[^"']+)["']/u.exec(body)?.groups?.url;
			return { name, address: `${method?.toUpperCase() ?? ""} ${url ?? ""}` };
		});
}

const OFFERED = new Map<unknown, string>(
	OPERATIONS.map(({ method, path, operation }) => [operation.operationId, `${method} ${path}`]),
);

const noop = () => undefined;

describe("the extension on an older server", () => {
	it("calls only what that release offers, at the same address", () => {
		const calls = callsOfTheClient();
		expect(calls.length).toBeGreaterThan(0);
		for (const { name, address } of calls) {
			expect({ name, address }).toStrictEqual({ name, address: OFFERED.get(name) });
		}
	});

	it.each([
		{ fields: "every field that may be null is null", nulls: true },
		{ fields: "every field that may be null has a value", nulls: false },
	])("shows a review from required fields alone, when $fields", async ({ nulls }) => {
		vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) =>
			answer(new Request(input, init), { nulls }),
		);
		const env = environment();

		const state = await resolveContext(env, 1);
		expect(state.status).toBe("ready");
		const [feedback, results] = await Promise.all([
			workFeedback(env, 1, "acme"),
			resultPage(env, 1, "acme"),
		]);

		const page = renderToStaticMarkup(
			createElement(PageReport, {
				state,
				feedback: { status: "ready", data: feedback },
				results: { status: "ready", data: results },
				activity: undefined,
				webAppOrigin: ISSUER.webAppOrigin,
				expanded: true,
				onToggle: noop,
				onRetry: noop,
				onRetryResults: noop,
				onOpenSettings: noop,
				onChooseWorkspace: noop,
				onAction: noop,
			}),
		);
		expect(page).not.toContain("We could not load");
		// That release sends no reason and no capture time, so a row claims neither.
		expect(results.rows.map((row) => [row.unverifiableReason, row.capturedAt])).toStrictEqual(
			results.rows.map(() => [null, null]),
		);
		vi.unstubAllGlobals();
	});
});
