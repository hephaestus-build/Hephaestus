import assert from "node:assert/strict";
import { test } from "node:test";

import {
	alertNames,
	catalogSlugs,
	closedConstant,
	DEEP_LINKS,
	exportedNames,
	findings,
	metricsIn,
} from "./check-public-contract.ts";

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
