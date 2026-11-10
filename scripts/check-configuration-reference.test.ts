import assert from "node:assert/strict";
import { test } from "node:test";

import { judge } from "./check-configuration-reference.ts";
import {
	chartRows,
	environmentGroup,
	helmGroup,
	join,
	Metadata,
	readPage,
	readSources,
	readSpringBindings,
	REFERENCE_PAGE,
	REFERENCE_PARTS,
	renderReference,
	requiredVariables,
	type Row,
} from "./lib/configuration-reference.ts";
import { applicationImage } from "./lib/deploy-topology.ts";

/** The page and its parts as one text, as the check reads them. */
const reference = (...args: Parameters<typeof renderReference>) =>
	[...renderReference(...args).values()].join("\n");

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
			description: "",
			defaultValue: undefined,
			type: "string",
			required: true,
			constraint: 'pattern: "@sha256:"',
		},
	]);
});
await test("unit descriptions keep validation bounds and describe each map value", () => {
	const rows = chartRows(
		{
			properties: {
				timeout: {
					type: "integer",
					minimum: 1,
					maximum: 60,
					description: "Seconds, parsed as an integer.",
				},
				storage: { type: "object", additionalProperties: { $ref: "#/definitions/size" } },
			},
			definitions: { size: { type: "string", description: "Bytes, parsed by Spring DataSize." } },
		},
		{ timeout: 10, storage: {} },
	);
	const page = reference([], rows);
	assert.match(page, /Seconds, parsed as an integer\./u);
	assert.match(page, /minimum: 1\. maximum: 60/u);
	assert.match(page, /Each value: Bytes, parsed by Spring DataSize\./u);
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
	assert.deepEqual(judge(requiredVariables(sources), reference(result.rows, [])), {
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
	assert.ok(readPage(reference([], sources.values)).some((row) => row.variable === "values.host"));
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

const row = (variable: string, setting: string): Row => ({
	variable,
	settings: [setting],
	defaultValue: "",
	services: ["server"],
	description: "A described setting.",
});

await test("each group of settings is its own part, so unrelated changes touch different files", () => {
	assert.equal(helmGroup("config.auth.passkeys.rpId"), "config.auth");
	assert.equal(helmGroup("config.llmUsageRetention"), "config");
	assert.equal(helmGroup("postgresql.backup.enabled"), "postgresql");
	assert.equal(helmGroup("host"), "top-level");
	assert.equal(environmentGroup(row("HEPHAESTUS_AUTH_X", "hephaestus.auth.x")), "hephaestus.auth");

	const rows = [
		row("HEPHAESTUS_AUTH_X", "hephaestus.auth.x"),
		row("SPRING_MAIL_HOST", "spring.mail.host"),
	];
	const files = renderReference(rows, []);
	const auth = [...files.entries()].find(([, text]) => text.includes("HEPHAESTUS_AUTH_X"));
	const mail = [...files.entries()].find(([, text]) => text.includes("SPRING_MAIL_HOST"));
	assert.ok(auth && mail);
	assert.notEqual(auth[0], mail[0], "two groups, two files");
	assert.ok(auth[0].startsWith(`${REFERENCE_PARTS}/`));
	// The same rows give the same files, so an unchanged setting never shows as a change.
	assert.deepEqual([...renderReference(rows, []).entries()], [...files.entries()]);
});

await test("the page keeps its anchors, imports every part, and every row reads back", async () => {
	const sources = await readSources();
	const rows = [
		row("HEPHAESTUS_AUTH_X", "hephaestus.auth.x"),
		row("SPRING_MAIL_HOST", "spring.mail.host"),
	];
	const files = renderReference(rows, sources.values);
	const page = files.get(REFERENCE_PAGE) ?? "";
	// The page had these two anchors before the split, and links may name them.
	assert.match(page, /^## Helm values$/mu);
	assert.match(page, /^## Application environment$/mu);
	assert.match(page, /^<HelmValues \/>$/mu);
	assert.match(page, /^<ApplicationEnvironment \/>$/mu);
	const indexes = [`${REFERENCE_PARTS}/helm.mdx`, `${REFERENCE_PARTS}/environment.mdx`]
		.map((file) => files.get(file) ?? "")
		.join("\n");
	for (const file of files.keys()) {
		const name = file.slice(REFERENCE_PARTS.length + 1, -".mdx".length);
		if (file !== REFERENCE_PAGE && name !== "helm" && name !== "environment") {
			assert.ok(indexes.includes(`./${name}.mdx`), `an index imports ${name}`);
		}
	}
	// A new group changes an index, never the page.
	const more = renderReference([...rows, row("NATS_SERVER", "nats.server")], sources.values);
	assert.equal(more.get(REFERENCE_PAGE), page);
	const read = readPage(reference(rows, sources.values)).map((entry) => entry.variable);
	assert.ok(read.includes("HEPHAESTUS_AUTH_X") && read.includes("SPRING_MAIL_HOST"));
	assert.equal(
		read.filter((variable) => variable.startsWith("values.")).length,
		sources.values.length,
	);
});

await test("native environment map entries join the map metadata to their rendered consumer", () => {
	const result = join(
		{
			bindings: [],
			values: [],
			containers: [
				{
					name: "Deployment/example-webhook:webhook",
					image: applicationImage,
					mounts: [],
					environment: new Map(
						["GITHUB", "GITLAB", "SLACK", "OUTLINE"].map((provider) => [
							`HEPHAESTUS_WEBHOOK_STREAM_MAXBYTESBYSTREAM_${provider}`,
							"1GB",
						]),
					),
				},
			],
		},
		new Metadata([
			{
				name: "hephaestus.webhook.stream.max-bytes-by-stream",
				type: "java.util.Map<java.lang.String,org.springframework.util.unit.DataSize>",
				description: "Storage bounds keyed by stream name.",
			},
		]),
	);
	assert.deepEqual(result.unbound, []);
	assert.deepEqual(
		result.rows.map(({ settings }) => settings),
		["github", "gitlab", "outline", "slack"].map((provider) => [
			`hephaestus.webhook.stream.max-bytes-by-stream[${provider}]`,
		]),
	);
	assert.equal(
		result.rows.every(
			({ services, description }) =>
				services.length === 1 &&
				services[0] === "webhook" &&
				description === "Storage bounds keyed by stream name.",
		),
		true,
	);
});

await test("native map names retain their canonical field boundary and reject misspellings", () => {
	const metadata = new Metadata([
		{
			name: "hephaestus.webhook.stream.max-age-by-stream",
			type: "java.util.Map<java.lang.String,java.time.Duration>",
		},
		{ name: "hephaestus.webhook.stream.max-bytes", type: "org.springframework.util.unit.DataSize" },
		{
			name: "hephaestus.webhook.stream.max-bytes-by-stream",
			type: "java.util.Map<java.lang.String,org.springframework.util.unit.DataSize>",
		},
		{
			name: "hephaestus.auth.login-providers",
			type: "java.util.Map<java.lang.String,de.tum.cit.aet.hephaestus.core.auth.AuthProperties$LoginProviderSeed>",
		},
	]);
	assert.equal(
		metadata.relaxedProperty("HEPHAESTUS_WEBHOOK_STREAM_MAXAGEBYSTREAM_SLACK")?.name,
		"hephaestus.webhook.stream.max-age-by-stream[slack]",
	);
	assert.equal(
		metadata.relaxedProperty("HEPHAESTUS_WEBHOOK_STREAM_MAX_AGE_BY_STREAM_SLACK"),
		undefined,
	);
	assert.equal(metadata.relaxedProperty("HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB"), undefined);
	assert.equal(metadata.relaxedProperty("HEPHAESTUS_AUTH_LOGINPROVIDERS_GITHUB"), undefined);
	assert.equal(
		metadata.relaxedProperty("HEPHAESTUS_WEBHOOK_STREAM_MAXAGEBYSTREM_SLACK"),
		undefined,
	);
});
