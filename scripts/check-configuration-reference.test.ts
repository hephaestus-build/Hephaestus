import assert from "node:assert/strict";
import { test } from "node:test";

import { judge } from "./check-configuration-reference.ts";
import {
	chartRows,
	join,
	Metadata,
	readPage,
	readSources,
	readSpringBindings,
	renderPage,
	requiredVariables,
} from "./lib/configuration-reference.ts";
import { applicationImage } from "./lib/deploy-topology.ts";

await test("schema leaves include release pins absent from source defaults", () => {
	const rows = chartRows(
		{
			properties: {
				images: {
					type: "object",
					required: ["server"],
					properties: { server: { $ref: "#/definitions/image" } },
				},
			},
			definitions: { image: { type: "string", pattern: "@sha256:" } },
		},
		{ images: {} },
	);
	assert.deepEqual(rows, [
		{
			key: "images.server",
			defaultValue: undefined,
			type: "string",
			required: true,
			constraint: 'pattern: "@sha256:"',
		},
	]);
});
await test("application variables join placeholder and relaxed names to rendered consumers", () => {
	const metadata = new Metadata([
		{ name: "hephaestus.enabled", description: "Enables practice feedback." },
		{ name: "hephaestus.poll", description: "Time between polls." },
	]);
	const sources = {
		bindings: readSpringBindings([
			["application.yml", `hephaestus:\n  enabled: \${FEATURE_ENABLED:false}\n`],
		]),
		containers: [
			{
				name: "worker",
				image: applicationImage,
				environment: new Map([
					["FEATURE_ENABLED", "true"],
					["HEPHAESTUS_POLL", "5s"],
					["JAVA_TOOL_OPTIONS", "-Xss1m"],
				]),
				mounts: [],
			},
		],
		values: [],
	};
	const result = join(sources, metadata);
	assert.deepEqual(result.unbound, []);
	assert.deepEqual(
		result.rows.map((row) => [row.variable, row.settings, row.services]),
		[
			["FEATURE_ENABLED", ["hephaestus.enabled"], ["worker"]],
			["HEPHAESTUS_POLL", ["hephaestus.poll"], ["worker"]],
		],
	);
	assert.deepEqual(judge(requiredVariables(sources), renderPage(result.rows, [])), {
		missing: [],
		stale: [],
		undescribed: [],
		flawed: [],
	});
});
await test("an unknown Java container variable is an unbound error", () => {
	assert.deepEqual(
		join(
			{
				bindings: [],
				values: [],
				containers: [
					{
						name: "worker",
						image: applicationImage,
						environment: new Map([["UNKNOWN", "x"]]),
						mounts: [],
					},
				],
			},
			new Metadata([]),
		).unbound,
		["worker: UNKNOWN"],
	);
});
await test("reference checks find missing, stale and undescribed settings", () => {
	const verdict = judge(
		new Set(["values.host", "PRESENT", "MISSING"]),
		"| `host` | string |\n| `PRESENT` | |\n| `STALE` | Old setting. |\n",
	);
	assert.deepEqual(verdict.missing, ["MISSING"]);
	assert.deepEqual(verdict.stale, ["STALE"]);
	assert.deepEqual(verdict.undescribed, ["PRESENT"]);
});
await test("the deploy inputs include chart values and no host Compose keys", async () => {
	const sources = await readSources();
	const required = requiredVariables(sources);
	assert.ok(required.has("values.postgresql.backup.restore.time"));
	assert.ok(required.has("values.worker.enabled"));
	assert.ok(required.has("DATABASE_PASSWORD"));
	assert.ok(!required.has("APP_HOSTNAME"));
	assert.ok(!required.has("SERVER_MEM"));
	assert.ok(readPage(renderPage([], sources.values)).some((row) => row.variable === "values.host"));
});

await test("nested Spring fallbacks retain both bindings and local profiles stay out", () => {
	const bindings = readSpringBindings([
		[
			"application.yml",
			`url: \${URL:http://localhost:\${PORT:8080}}\n---\nspring:\n  config:\n    activate:\n      on-profile: local\nlocal: \${LOCAL_ONLY:x}\n`,
		],
	]);
	assert.deepEqual(
		bindings.map((binding) => binding.variable),
		["URL", "PORT"],
	);
});
