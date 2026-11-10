import assert from "node:assert/strict";
import { globSync, readFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { parse } from "yaml";

import {
	alertNames,
	catalogSlugs,
	closedConstant,
	DEEP_LINKS,
	exportedNames,
	findings,
	metricsIn,
	problemSlugs,
	secretKeys,
} from "./check-public-contract.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";

const CHART = "deploy/chart/hephaestus/values.schema.json";
const ALERTS = "deploy/chart/hephaestus/files/hephaestus-alerts.yml";
const CATALOG_SCHEMA =
	"server/application/src/main/resources/practices/default-catalog.schema.json";
const CATALOG = "server/application/src/main/resources/practices/default-catalog.json";
const CONTRACT = "docker/agents/practice-scripts/lib/core/contract.ts";
const ENTRY = "docker/agents/practice-scripts/lib/api.ts";
const SCM = "docker/agents/practice-scripts/kits/scm/api.ts";
const CHAT = "docker/agents/practice-scripts/kits/chat/index.ts";
const DOCS = "docker/agents/practice-scripts/kits/docs/index.ts";
const PROBLEMS =
	"server/application/src/main/java/de/tum/cit/aet/hephaestus/core/exception/ProblemType.java";
const TEMPLATE = "deploy/chart/hephaestus/templates/server.yaml";

const secretEnv = (env: string, optional = false) =>
	`{{ include "hephaestus.secretEnv" (dict "root" . "env" "${env}"${optional ? ' "optional" true' : ""}) }}\n`;

const chart = (worker: object) =>
	JSON.stringify({
		type: "object",
		properties: { worker: { type: "object", properties: worker } },
	});

const RELEASE = new Map([
	[
		CHART,
		chart({
			reviews: { type: "integer", minimum: 1, maximum: 64 },
			strategy: { type: "string", enum: ["RollingUpdate", "Recreate"] },
		}),
	],
	[
		ALERTS,
		`groups:
  - name: hephaestus
    rules:
      - alert: QueueStalled
        expr: max(agent_queue_oldest_age_seconds{job="hephaestus"}) > 3600
`,
	],
	[
		CATALOG_SCHEMA,
		JSON.stringify({ type: "object", properties: { $defs: {}, groups: { type: "array" } } }),
	],
	[
		CATALOG,
		JSON.stringify({ groups: [{ slug: "reviews", practices: [{ slug: "clear-comments" }] }] }),
	],
	[
		CONTRACT,
		`export const CONTRACT_VERSION = 3 as const;
export const OUTCOMES = ["MET", "NOT_MET"] as const;
export const SEVERITIES = ["MAJOR"] as const;
`,
	],
	[
		ENTRY,
		"export const definePracticeScript = () => {};\nexport type { Outcome } from './core/practice-script.ts';\n",
	],
	[PROBLEMS, "public enum ProblemType {\n    STEP_UP_REQUIRED,\n    SYNC_NOT_SUPPORTED;\n}\n"],
	[TEMPLATE, secretEnv("WEBHOOK_SECRET") + secretEnv("GH_AUTH_TOKEN", true)],
	[SCM, "export function scm() {}\n"],
	[CHAT, "export function chat() {}\n"],
	[DOCS, "export function docs() {}\n"],
]);

const ROUTES = DEEP_LINKS.map((route) => `'${route}': typeof Route`).join("\n");

const after = (file: string, text: string) => new Map([...RELEASE, [file, text]]);

void test("a release compared with itself loses nothing", () => {
	assert.deepEqual(findings(RELEASE, RELEASE, ROUTES), []);
});

void test("additions are compatible", () => {
	const head = new Map([
		...RELEASE,
		[
			CHART,
			chart({
				reviews: { type: "integer", minimum: 0, maximum: 128 },
				strategy: { type: "string", enum: ["RollingUpdate", "Recreate", "Blue"] },
				turns: { type: "integer" },
			}),
		],
		[
			CATALOG,
			JSON.stringify({
				groups: [{ slug: "reviews", practices: [{ slug: "clear-comments" }, { slug: "new-one" }] }],
			}),
		],
		[ENTRY, `${RELEASE.get(ENTRY) ?? ""}export function added() {}\n`],
		[
			PROBLEMS,
			"public enum ProblemType {\n    STEP_UP_REQUIRED,\n    SYNC_NOT_SUPPORTED,\n    NEW_KIND;\n}\n",
		],
		[TEMPLATE, `${RELEASE.get(TEMPLATE) ?? ""}${secretEnv("SPRING_MAIL_PASSWORD", true)}`],
		["deploy/chart/hephaestus/templates/new.yaml", ""],
	]);
	assert.deepEqual(findings(RELEASE, head, ROUTES), []);
});

for (const [name, head, expected] of [
	[
		"a lost chart value",
		after(CHART, chart({ reviews: { type: "integer", minimum: 1, maximum: 64 } })),
		/Chart value worker\.strategy is gone/u,
	],
	[
		"a lower maximum",
		after(
			CHART,
			chart({
				reviews: { type: "integer", minimum: 1, maximum: 32 },
				strategy: { type: "string", enum: ["RollingUpdate", "Recreate"] },
			}),
		),
		/worker\.reviews: maximum 64 became 32/u,
	],
	[
		"a higher minimum",
		after(
			CHART,
			chart({
				reviews: { type: "integer", minimum: 2, maximum: 64 },
				strategy: { type: "string", enum: ["RollingUpdate", "Recreate"] },
			}),
		),
		/worker\.reviews: minimum 1 became 2/u,
	],
	[
		"a lost enum value",
		after(
			CHART,
			chart({
				reviews: { type: "integer", minimum: 1, maximum: 64 },
				strategy: { type: "string", enum: ["RollingUpdate"] },
			}),
		),
		/worker\.strategy: value "Recreate" is gone/u,
	],
	[
		"a lost type",
		after(
			CHART,
			chart({
				reviews: { type: "string", minimum: 1, maximum: 64 },
				strategy: { type: "string", enum: ["RollingUpdate", "Recreate"] },
			}),
		),
		/worker\.reviews: type integer is gone/u,
	],
	[
		"a new required Secret key",
		after(TEMPLATE, `${RELEASE.get(TEMPLATE) ?? ""}${secretEnv("NATS_WEBHOOK_PASSWORD")}`),
		/requires the Secret key NATS_WEBHOOK_PASSWORD/u,
	],
	[
		"an optional Secret key made required",
		after(TEMPLATE, secretEnv("WEBHOOK_SECRET") + secretEnv("GH_AUTH_TOKEN")),
		/requires the Secret key GH_AUTH_TOKEN/u,
	],
	[
		"a lost problem type",
		after(PROBLEMS, "public enum ProblemType {\n    STEP_UP_REQUIRED;\n}\n"),
		/problem type sync-not-supported is gone/u,
	],
	[
		"a narrowed definition field",
		after(
			CATALOG_SCHEMA,
			JSON.stringify({ type: "object", properties: { $defs: {}, groups: { type: "object" } } }),
		),
		/definition schema narrowed groups: type array is gone/u,
	],
	[
		"a lost alert",
		after(ALERTS, "groups:\n  - name: hephaestus\n    rules: []\n"),
		/lost alert QueueStalled/u,
	],
	[
		"a renamed metric",
		after(
			ALERTS,
			`groups:\n  - name: hephaestus\n    rules:\n      - alert: QueueStalled\n        expr: max(review_queue_oldest_age_seconds) > 3600\n`,
		),
		/lost metric agent_queue_oldest_age_seconds/u,
	],
	[
		"a lost definition field",
		after(CATALOG_SCHEMA, JSON.stringify({ type: "object", properties: {} })),
		/definition schema lost groups/u,
	],
	[
		"a lost practice slug",
		after(CATALOG, JSON.stringify({ groups: [{ slug: "reviews", practices: [] }] })),
		/catalog lost practice clear-comments/u,
	],
	[
		"a lost group slug",
		after(
			CATALOG,
			JSON.stringify({ groups: [{ slug: "other", practices: [{ slug: "clear-comments" }] }] }),
		),
		/catalog lost group reviews/u,
	],
	[
		"a lost script export",
		after(ENTRY, "export const definePracticeScript = () => {};\n"),
		/lib\/api\.ts no longer exports Outcome/u,
	],
	[
		"a new contract version",
		after(CONTRACT, (RELEASE.get(CONTRACT) ?? "").replace("= 3", "= 4")),
		/CONTRACT_VERSION changed from 3 to 4/u,
	],
	[
		"a fifth outcome",
		after(CONTRACT, (RELEASE.get(CONTRACT) ?? "").replace('"NOT_MET"]', '"NOT_MET", "PARTLY"]')),
		/OUTCOMES changed/u,
	],
] as const) {
	void test(`${name} is refused`, () => {
		assert.match(findings(RELEASE, head, ROUTES).join("\n"), expected);
	});
}

const sized = (size: object) =>
	after(
		CHART,
		chart({
			reviews: { type: "integer", minimum: 1, maximum: 64 },
			strategy: { type: "string", enum: ["RollingUpdate", "Recreate"] },
			size,
		}),
	);

void test("a changed pattern is refused unless the old one stays as a branch", () => {
	const base = sized({ type: "string", pattern: "^[0-9]+Mi$" });
	assert.deepEqual(findings(base, sized({ type: "string", pattern: "^[0-9]+Gi$" }), ROUTES), [
		"Chart value worker.size: pattern ^[0-9]+Mi$ is gone",
	]);
	const widened = sized({
		anyOf: [
			{ type: "string", pattern: "^[0-9]+Mi$" },
			{ type: "string", pattern: "^[0-9]+Gi$" },
		],
	});
	assert.deepEqual(findings(base, widened, ROUTES), []);
	assert.deepEqual(findings(base, sized({ type: "string" }), ROUTES), []);
});

void test("Secret keys come from secretEnv calls and secretKeyRef blocks", () => {
	assert.deepEqual(
		[
			...secretKeys(`{{ include "hephaestus.secretEnv" (dict "root" . "env" "PGPASSWORD" "key" "DATABASE_ADMIN_PASSWORD") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "GH_AUTH_TOKEN" "optional" true) }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "NATS_PASSWORD" "optional" true) }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "NATS_PASSWORD") }}
- name: PGBACKREST_PG1_USER
  valueFrom:
    secretKeyRef:
      name: {{ .Values.secret.name }}
      key: DATABASE_ADMIN_USERNAME
      optional: true
`),
		],
		[
			["DATABASE_ADMIN_PASSWORD", true],
			["GH_AUTH_TOKEN", false],
			["NATS_PASSWORD", true],
			["DATABASE_ADMIN_USERNAME", false],
		],
	);
});

void test("problem slugs are the enum constants in their wire form", () => {
	assert.deepEqual(
		[
			...problemSlugs(`public enum ProblemType {
    // A comment, not a constant.
    CSRF_TOKEN_REJECTED,
    STEP_UP_REQUIRED;

    private static final String BASE = "x";
}`),
		],
		["csrf-token-rejected", "step-up-required"],
	);
});

void test("a deep link without a route is refused", () => {
	const routes = ROUTES.replace("'/settings': typeof Route", "");
	assert.deepEqual(findings(RELEASE, RELEASE, routes), [
		"Posted links lead to /settings, which no route serves",
	]);
});

void test("a moved file names the list to update, instead of passing", () => {
	const head = new Map(RELEASE);
	head.delete(SCM);
	assert.throws(() => findings(RELEASE, head, ROUTES), /Update the file list/u);
});

void test("the metrics of an expression leave out labels, functions, strings and comments", () => {
	assert.deepEqual(
		metricsIn(`# Reads the age of the oldest message, which no other rule reads.
sum by (stream) (increase(webhook_publish_total{job="hephaestus",outcome="failure"}[10m])) > 0
and on (stream) label_replace(up{job="x"}, "a", "b", "c", "d")
unless time() - backup_seconds offset 5m > 2400`),
		["webhook_publish_total", "up", "backup_seconds"],
	);
});

void test("alert names and catalog slugs read each kind", () => {
	assert.deepEqual(
		[...alertNames(RELEASE.get(ALERTS) ?? "")],
		["alert QueueStalled", "metric agent_queue_oldest_age_seconds"],
	);
	assert.deepEqual(
		[...catalogSlugs(RELEASE.get(CATALOG) ?? "")],
		["group reviews", "practice clear-comments"],
	);
});

void test("exports come from declarations and lists, with aliases and types", () => {
	assert.deepEqual(
		[
			...exportedNames(`export const a = 1;
export async function b() {}
export interface C {}
export { d, e as f, type G } from "./x.ts";
export type { H } from "./y.ts";
const hidden = 2;
export default hidden;
`),
		],
		["a", "b", "C", "d", "f", "G", "H", "default"],
	);
});

void test("a whole-module export and a default count as exports", () => {
	assert.deepEqual(
		[...exportedNames('export * from "./a.ts";\nexport const enum E {}\nexport default 1;\n')],
		["E", 'export * from "./a.ts"', "default"],
	);
});

const nestedReads = (reads: object) =>
	JSON.stringify({
		type: "object",
		properties: {
			definition: {
				type: "object",
				$defs: { practiceReads: { type: "object", properties: reads } },
				properties: { reads: { $ref: "#/$defs/practiceReads" } },
			},
		},
	});

void test("a removed field behind a nested definition counts as lost", () => {
	const base = after(CATALOG_SCHEMA, nestedReads({ contract: {}, sources: {} }));
	const head = after(CATALOG_SCHEMA, nestedReads({ contract: {} }));
	assert.deepEqual(findings(base, head, ROUTES), [
		"The practice definition schema lost definition.reads.sources",
	]);
});

const limitedChart = (maximum: number | undefined, listMaximum: number) =>
	JSON.stringify({
		type: "object",
		definitions: { port: { type: "integer", maximum: listMaximum } },
		properties: {
			worker: {
				type: "object",
				properties: {
					turns: {
						oneOf: [
							{ const: "auto" },
							{ type: "integer", ...(maximum !== undefined && { maximum }) },
						],
					},
					ports: { type: "array", items: { $ref: "#/definitions/port" } },
				},
			},
		},
	});

void test("limits behind a reference, a branch and list items count; a wider limit is fine", () => {
	const base = after(CHART, limitedChart(64, 65_535));
	assert.deepEqual(findings(base, after(CHART, limitedChart(64, 65_535)), ROUTES), []);
	assert.deepEqual(findings(base, after(CHART, limitedChart(128, 65_535)), ROUTES), []);
	assert.deepEqual(findings(base, after(CHART, limitedChart(undefined, 65_535)), ROUTES), []);
	assert.deepEqual(findings(base, after(CHART, limitedChart(32, 1024)), ROUTES), [
		"Chart value worker.turns: maximum 64 became 32",
		"Chart value worker.ports[]: maximum 65535 became 1024",
	]);
	const unbounded = after(CHART, limitedChart(undefined, 65_535));
	assert.deepEqual(findings(unbounded, after(CHART, limitedChart(64, 65_535)), ROUTES), [
		"Chart value worker.turns: maximum undefined became 64",
	]);
});

void test("dropping an enum accepts more, so no value is lost", () => {
	const open = chart({
		reviews: { type: "integer", minimum: 1, maximum: 64 },
		strategy: { type: "string" },
	});
	assert.deepEqual(findings(RELEASE, after(CHART, open), ROUTES), []);
});

void test("a closed constant reads across lines and ignores spacing", () => {
	assert.equal(
		closedConstant('export const OUTCOMES = [\n\t"MET",\n\t"NOT_MET",\n] as const;\n', "OUTCOMES"),
		'["MET","NOT_MET",]',
	);
	assert.equal(closedConstant("export const OTHER = 1 as const;\n", "OUTCOMES"), undefined);
});

function readRepositoryFile(file: string) {
	return readFileSync(new URL(`../${file}`, import.meta.url), "utf8");
}

function normalizeApiResource(resource: string) {
	return resource
		.replace(/^\/api(?=\/)/u, "")
		.replace(/\?.*$/u, "")
		.replace(/[.,;:]$/u, "")
		.replaceAll(/\{[^/]+?\}|<[^/]+?>/gu, "{}");
}

void test("the public operation list covers existing documented methods and extension SDK imports", () => {
	const promised = asArray(
		parseJson(readRepositoryFile("server/public-api-operations.json")),
		"public operations",
	).map((value) => asString(value, "public operation"));
	assert.notEqual(promised.length, 0);
	assert.equal(new Set(promised).size, promised.length, "duplicate public operation");
	const specification = asRecord(parse(readRepositoryFile("server/openapi.yaml")), "OpenAPI");
	const paths = asRecord(specification.paths, "OpenAPI paths");
	const methods = new Set(["get", "post", "put", "patch", "delete", "head", "options", "trace"]);
	const operations = new Map<string, string>();
	for (const [resource, raw] of Object.entries(paths)) {
		for (const [method, value] of Object.entries(asRecord(raw, resource))) {
			if (!methods.has(method)) {
				continue;
			}
			const operation = asRecord(value, `${method} ${resource}`);
			operations.set(
				asString(operation.operationId, "operation ID"),
				`${method.toUpperCase()} ${resource}`,
			);
		}
	}
	for (const operation of promised) {
		assert.ok(
			[...operations.values()].includes(operation),
			`Unknown public operation: ${operation}`,
		);
	}

	const extension = fileURLToPath(new URL("../extension/src/", import.meta.url));
	const imported = new Set<string>();
	for (const file of globSync("**/*.{ts,tsx}", {
		cwd: extension,
		exclude: ["api/**", "**/*.test.*", "testing/**"],
	})) {
		const source = readFileSync(path.join(extension, file), "utf8");
		for (const match of source.matchAll(
			/import\s*\{(?<names>[^}]+)\}\s*from\s*["']~\/api\/sdk\.gen["']/gu,
		)) {
			const { groups } = match;
			assert.ok(groups !== undefined);
			const { names } = groups;
			assert.ok(names !== undefined && names !== "");
			for (const binding of names.split(",")) {
				const name = binding.trim().split(/\s+as\s+/u)[0];
				if (name === undefined || name === "" || name.startsWith("type ")) {
					continue;
				}
				const operation = operations.get(name);
				assert.ok(
					operation !== undefined && operation !== "",
					`${file} imports unknown SDK operation ${name}`,
				);
				imported.add(operation);
				assert.ok(
					promised.includes(operation),
					`${file} uses ${operation} outside the public promise`,
				);
			}
		}
	}
	assert.notEqual(imported.size, 0, "extension operations were not inspected");

	const canonical = new Map(
		[...operations.values()].map((operation) => {
			const [method, resource] = operation.split(" ");
			assert.ok(method !== undefined && resource !== undefined);
			assert.notEqual(method, "");
			assert.notEqual(resource, "");
			return [`${method} ${normalizeApiResource(resource)}`, operation];
		}),
	);
	const root = fileURLToPath(new URL("../", import.meta.url));
	for (const file of globSync("docs/{admin,user}/**/*.{md,mdx}", {
		cwd: root,
		exclude: ["docs/admin/_configuration-reference/**"],
	})) {
		const text = readFileSync(path.join(root, file), "utf8").replaceAll("`", "");
		for (const match of text.matchAll(
			/\b(?<verbs>(?:(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)(?:\s*(?:,|and|or)\s*)?)+)\s+(?<resource>\/[^\s]+)/gu,
		)) {
			const { groups } = match;
			assert.ok(groups !== undefined);
			const { verbs, resource } = groups;
			assert.ok(verbs !== undefined && resource !== undefined);
			assert.notEqual(verbs, "");
			assert.notEqual(resource, "");
			for (const method of verbs.match(/GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE/gu) ?? []) {
				const operation = canonical.get(`${method} ${normalizeApiResource(resource)}`);
				if (operation !== undefined) {
					assert.ok(
						promised.includes(operation),
						`${file} documents ${operation} outside the public promise`,
					);
				}
			}
		}
	}
});
