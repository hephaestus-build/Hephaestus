import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import Ajv from "ajv";
import { parse } from "yaml";

import { parseInventory } from "./commit-image-lock.ts";
import {
	chartImages,
	chartValues,
	commitChartValues,
	releaseValuesFile,
} from "./kubernetes-chart.ts";
import { asRecord, isRecord, parseJson, readJsonFileSync } from "./lib/json.ts";
import { parseReleaseImageLock } from "./release-image-lock.ts";

const chart = new URL("../deploy/chart/hephaestus/", import.meta.url);
const read = (relative: string) => readFileSync(new URL(relative, chart), "utf8");
const inventory = parseInventory(
	readJsonFileSync(fileURLToPath(new URL("../security/release-images.json", import.meta.url))),
);
const schema = asRecord(parseJson(read("values.schema.json")), "values schema");
const validate = new Ajv({ allErrors: true, strict: true }).compile(schema);
const defaults = asRecord(parse(read("values.yaml")), "values.yaml");
const profiles = readdirSync(new URL("profiles/", chart)).filter((name) => name.endsWith(".yaml"));
const templates = readdirSync(new URL("templates/", chart)).map((name) => ({
	name,
	source: read(`templates/${name}`),
}));

const digest = (character: string) => `sha256:${character.repeat(64)}`;
const platforms = { "linux/amd64": digest("a"), "linux/arm64": digest("b") };
const rawLock = {
	schemaVersion: 1,
	release: "v1.2.3",
	commit: "c".repeat(40),
	images: [
		...inventory.images.map((image, index) => ({
			image,
			repository: `ghcr.io/hephaestus-build/${image}`,
			provenance: "first-party",
			indexDigest: digest(String(index)),
			platforms,
		})),
		...inventory.upstream.map((entry) => ({
			image: entry.name,
			repository: entry.repository,
			provenance: "upstream",
			indexDigest: entry.digest,
			platforms,
		})),
	],
};
const lockWith = (images: typeof rawLock.images) => parseReleaseImageLock({ ...rawLock, images });

/** Helm's merge: maps merge key by key, every other value replaces. */
function merge(base: unknown, override: unknown): unknown {
	if (!isRecord(base) || !isRecord(override)) {
		return override;
	}
	const merged: Record<string, unknown> = { ...base };
	for (const [key, value] of Object.entries(override)) {
		merged[key] = merge(base[key], value);
	}
	return merged;
}

/** The values an operator supplies on top of a profile and the prepared file. */
const operator = {
	host: "hephaestus.example.com",
	trustedProxies: String.raw`10\.42\.\d{1,3}\.\d{1,3}`,
	secret: { name: "hephaestus" },
	route: { parentRefs: [{ name: "edge", namespace: "gateway-system", sectionName: "https" }] },
	networkPolicy: {
		gateway: {
			namespace: "gateway-system",
			podLabels: { "gateway.networking.k8s.io/gateway-name": "edge" },
		},
	},
};
function install(...layers: unknown[]): unknown {
	let merged: unknown = defaults;
	for (const layer of layers) {
		merged = merge(merged, layer);
	}
	return merged;
}
const prepared = chartValues(lockWith(rawLock.images), inventory);

await test("a complete lock names every chart image by its locked index digest", () => {
	assert.deepEqual(Object.keys(prepared.images), [...chartImages]);
	assert.deepEqual(prepared.release, { version: "v1.2.3", commit: "c".repeat(40) });
	for (const name of chartImages) {
		const image = rawLock.images.find((entry) => entry.image === name);
		assert.ok(image, name);
		assert.equal(prepared.images[name], `${image.repository}@${image.indexDigest}`);
	}
});

await test("a released chart's values.yaml pins the release, and changes nothing else", () => {
	const sourceYaml = read("values.yaml");
	const released = releaseValuesFile(sourceYaml, prepared);
	const values = asRecord(parse(released), "released values.yaml");
	assert.deepEqual(values.release, prepared.release);
	assert.deepEqual(values.images, prepared.images);
	assert.deepEqual({ ...values, release: defaults.release, images: defaults.images }, defaults);
	assert.doesNotMatch(released, /:(?:latest|\d+\.\d+)"?\s*$/mu);
	// The schema accepts the released defaults with the operator's values alone, and an operator's
	// image that is not a digest still fails on top of them.
	assert.equal(validate(merge(values, operator)), true, JSON.stringify(validate.errors));
	assert.equal(
		validate(
			merge(values, merge(operator, { images: { agent: "ghcr.io/hephaestus-build/agent:1.2.3" } })),
		),
		false,
	);
	// Moved release lines are refused here. Lines that survive but lose to another key are refused
	// when release-chart.ts reads Helm's parse of the package back.
	for (const [label, changed] of [
		["no release lines", sourceYaml.replace("images: {}\n", "images:\n")],
		["indented release lines", sourceYaml.replace("\nrelease:\n", "\n release:\n")],
	] as const) {
		assert.throws(
			() => releaseValuesFile(changed, prepared),
			/does not hold its empty release values/u,
			label,
		);
	}
});

await test("a joint lock names the same chart images as its schema 1 form", () => {
	const joint = parseReleaseImageLock({
		...rawLock,
		schemaVersion: 2,
		chart: {
			repository: "ghcr.io/hephaestus-build/charts/hephaestus",
			version: "1.2.3",
			digest: digest("f"),
		},
	});
	assert.deepEqual(chartValues(joint, inventory), prepared);
});

await test("a lock that does not cover exactly the release image inventory is refused", () => {
	assert.throws(
		() =>
			chartValues(lockWith(rawLock.images.filter((image) => image.image !== "vmalert")), inventory),
		/missing: vmalert; extra: none/u,
	);
	const rogue = {
		image: "rogue",
		repository: "ghcr.io/hephaestus-build/rogue",
		provenance: "first-party",
		indexDigest: digest("9"),
		platforms,
	};
	assert.throws(
		() => chartValues(lockWith([...rawLock.images, rogue]), inventory),
		/missing: none; extra: rogue/u,
	);
	assert.throws(
		() =>
			chartValues(
				lockWith(
					rawLock.images.map((image) =>
						image.image === "agent" ? { ...image, provenance: "upstream" } : image,
					),
				),
				inventory,
			),
		/agent has the wrong provenance class/u,
	);
	assert.throws(
		() =>
			chartValues(
				lockWith(
					rawLock.images.map((image) =>
						image.image === "vmalert"
							? { ...image, repository: "docker.io/library/vmalert" }
							: image,
					),
				),
				inventory,
			),
		/vmalert is locked from docker\.io\/library\/vmalert/u,
	);
	assert.throws(
		() =>
			chartValues(
				lockWith(
					rawLock.images.map((image) =>
						image.image === "vmalert" ? { ...image, indexDigest: digest("e") } : image,
					),
				),
				inventory,
			),
		/vmalert is locked at sha256:e{64}, not the inventory pin/u,
	);
});

await test("a commit lock names the chart images by digest, its upstream images at the inventory pins", () => {
	const commit = "c".repeat(40);
	const pin = (name: string) => {
		const entry = inventory.upstream.find((candidate) => candidate.name === name);
		assert.ok(entry, name);
		return `${entry.repository}@${entry.digest}`;
	};
	const images = {
		HEPHAESTUS_IMAGE_AGENT: `ghcr.io/hephaestus-build/agent@${digest("1")}`,
		HEPHAESTUS_IMAGE_ALERTMANAGER: pin("alertmanager"),
		HEPHAESTUS_IMAGE_SERVER: `ghcr.io/hephaestus-build/server@${digest("2")}`,
		HEPHAESTUS_IMAGE_VICTORIA_METRICS: pin("victoria-metrics"),
		HEPHAESTUS_IMAGE_VMALERT: pin("vmalert"),
		HEPHAESTUS_IMAGE_POSTGRES: `ghcr.io/hephaestus-build/postgres@${digest("4")}`,
		HEPHAESTUS_IMAGE_POSTGRES_EXPORTER: pin("postgres-exporter"),
		// An image the chart does not run.
		HEPHAESTUS_IMAGE_TRAEFIK: `docker.io/library/traefik@${digest("6")}`,
	};
	const values = commitChartValues(images, inventory, commit, chartImages);
	assert.deepEqual(Object.keys(values.images), [...chartImages]);
	assert.equal(values.images.postgres, images.HEPHAESTUS_IMAGE_POSTGRES);
	// A followed commit reports the commit as its version, which the promotion verifies.
	assert.equal(values.release.version, commit);

	const { HEPHAESTUS_IMAGE_AGENT: _agent, ...withoutAgent } = images;
	assert.throws(
		() => commitChartValues(withoutAgent, inventory, commit, chartImages),
		/no agent digest/u,
	);
	assert.throws(
		() =>
			commitChartValues(
				{
					...images,
					HEPHAESTUS_IMAGE_VMALERT: pin("vmalert").replace(
						/@sha256:[0-9a-f]+$/u,
						`@${digest("e")}`,
					),
				},
				inventory,
				commit,
				chartImages,
			),
		/vmalert is locked at .*, not the inventory pin/u,
	);
	assert.throws(
		() =>
			commitChartValues(
				{ ...images, HEPHAESTUS_IMAGE_AGENT: "ghcr.io/hephaestus-build/agent:main" },
				inventory,
				commit,
				chartImages,
			),
		/no agent digest/u,
	);
});

await test("commit chart values follow the applied inventory across image contract changes", () => {
	const commit = "d".repeat(40);
	const engine = `ghcr.io/example/custom-service@${digest("a")}`;
	const executor = `ghcr.io/another-team/runtime@${digest("b")}`;
	const broker = `docker.io/example/message-bus@${digest("c")}`;
	const candidate = parseInventory({
		images: ["engine", "executor", "scan-only"],
		upstream: [
			{ name: "broker", repository: "docker.io/example/message-bus", digest: digest("c") },
		],
	});
	assert.deepEqual(
		commitChartValues(
			{
				HEPHAESTUS_IMAGE_ENGINE: engine,
				HEPHAESTUS_IMAGE_EXECUTOR: executor,
				HEPHAESTUS_IMAGE_BROKER: broker,
			},
			candidate,
			commit,
			["broker", "engine", "executor"],
		),
		{ release: { version: commit, commit }, images: { broker, engine, executor } },
	);
	assert.throws(
		() =>
			commitChartValues(
				{ HEPHAESTUS_IMAGE_ENGINE: engine, HEPHAESTUS_IMAGE_BROKER: broker },
				candidate,
				commit,
				["broker", "engine", "executor"],
			),
		/no executor digest/u,
	);
	assert.throws(
		() =>
			commitChartValues(
				{
					HEPHAESTUS_IMAGE_ENGINE: engine,
					HEPHAESTUS_IMAGE_EXECUTOR: executor,
					HEPHAESTUS_IMAGE_BROKER: `docker.io/example/message-bus@${digest("d")}`,
				},
				candidate,
				commit,
				["broker", "engine", "executor"],
			),
		/not the inventory pin/u,
	);
});

await test("the chart ships no image, tag or release identity of its own", () => {
	assert.deepEqual(defaults.images, {});
	assert.deepEqual(defaults.release, { version: "", commit: "" });
	assert.equal(validate(install(operator)), false, "values without the prepared file must fail");
	const images = asRecord(at(schema, ["properties", "images"]), "images schema");
	assert.deepEqual(images.required, [...chartImages]);
	assert.equal(images.additionalProperties, false);
	for (const reference of [
		"ghcr.io/hephaestus-build/agent:latest",
		"ghcr.io/hephaestus-build/agent:1.2.3",
		`registry.example.com/agent@${digest("1")}`,
		"ghcr.io/hephaestus-build/agent@sha256:short",
	]) {
		const values = install(prepared, operator, { images: { agent: reference } });
		assert.equal(validate(values), false, reference);
	}
});

const load = (name: string) => asRecord(parse(read(`profiles/${name}`)), name);

await test("every profile installs from the same templates with only the operator's values", () => {
	assert.deepEqual(profiles.toSorted(), ["cluster.yaml", "dev.yaml", "small.yaml"]);
	for (const name of profiles) {
		const profile = load(name);
		for (const owned of ["release", "images", "secret", "host", "trustedProxies", "route"]) {
			assert.equal(owned in profile, false, `${name} sets ${owned}`);
		}
		const values = install(profile, prepared, operator);
		assert.equal(validate(values), true, `${name}: ${JSON.stringify(validate.errors)}`);
		// The small profile runs reviews; the operator states the sandbox namespace and API endpoint.
		assert.equal(
			at(values, ["sandbox", "enabled"]),
			name === "small.yaml" || name === "cluster.yaml",
			`${name} sandboxes`,
		);
	}
});

await test("the values that the repository shows an operator fit the schema", () => {
	const repository = new URL("../", import.meta.url);
	const yaml = (relative: string) =>
		asRecord(parse(readFileSync(new URL(relative, repository), "utf8")), relative);
	const admin = new URL("docs/admin/", repository);
	const documented = readdirSync(admin, { recursive: true, encoding: "utf8" })
		.filter((name) => /\.mdx?$/u.test(name))
		.flatMap((name) =>
			[
				...readFileSync(new URL(name, admin), "utf8").matchAll(
					/^(?<indent> *)```yaml title="(?:values|site)\.yaml"\n(?<block>.*?)^\k<indent>```$/gmsu,
				),
			].map((match) => ({ name, values: asRecord(parse(match.groups?.block ?? ""), name) })),
		);
	assert.notEqual(documented.length, 0, "the documented values must stay findable");
	for (const { name, values } of documented) {
		const merged = install(prepared, operator, values);
		assert.equal(validate(merged), true, `${name}: ${JSON.stringify(validate.errors)}`);
	}
	const small = load("small.yaml");
	const examples = [
		["k3s-values.yaml", [small, prepared, operator, yaml("deploy/platform/k3s-values.yaml")]],
		["capacity-bench/values.yaml", [small, prepared, yaml("scripts/capacity-bench/values.yaml")]],
	] as const;
	for (const [name, layers] of examples) {
		assert.equal(validate(install(...layers)), true, `${name}: ${JSON.stringify(validate.errors)}`);
	}
});

await test("the schema refuses a value that it does not name, at every level", () => {
	const valid = install(prepared, operator);
	for (const change of [
		{ removed: true },
		{ config: { gitlab: { workspaceCreation: false } } },
		{ config: { gitCheckoutEnabled: true } },
		{ config: { gitCheckoutEnabled: false } },
		{ config: { durableConsumerName: "hephaestus" } },
		{ config: { durableConsumerName: "custom" } },
		{ config: { consumerInactiveThreshold: "30d" } },
		{ config: { consumerInactiveThreshold: "0s" } },
		{ config: { slack: { redirectUri: "https://example.test/api/oauth/callback/slack" } } },
	]) {
		assert.equal(validate(merge(valid, change)), false, JSON.stringify(change));
	}
});

await test("the schema refuses settings the chart does not own", () => {
	const valid = install(prepared, operator);
	assert.equal(validate(valid), true, JSON.stringify(validate.errors));
	assert.equal(at(defaults, ["server", "replicas"]), 2);
	for (const replicas of [0, 1, 2, 3]) {
		assert.equal(validate(merge(valid, { server: { replicas } })), true, `server at ${replicas}`);
	}
	for (const [label, change] of [
		["a generic environment hatch", { server: { extraEnv: [{ name: "X", value: "y" }] } }],
		["a pod spec hatch", { server: { podSpec: { hostNetwork: true } } }],
		["a negative server count", { server: { replicas: -1 } }],
		["a fractional server count", { server: { replicas: 0.5 } }],
		["the retired worker role", { worker: { replicas: 1 } }],
		["an inline secret value", { secret: { name: "hephaestus", WEBHOOK_SECRET: "x" } }],
		["an sslmode the chart does not offer", { postgresql: { external: { sslmode: "allow" } } }],
		["network policy turned off", { networkPolicy: { enabled: false } }],
		["no Gateway to attach to", { route: { parentRefs: [] } }],
		["a Gateway the chart creates", { route: { gateway: { create: true } } }],
		["the retired rollout setting", { server: { rolloutStrategy: "Recreate" } }],
		["a class name beside classes the chart creates", { priorityClasses: { roles: "high" } }],
		[
			"existing classes without all three names",
			{ priorityClasses: { create: false, roles: "high", conversations: "middle" } },
		],
		["a sandbox namespace that is no DNS label", { sandbox: { namespace: "Sandboxes" } }],
		["a third sandbox namespace", { sandbox: { other: { quota: {} } } }],
		["a git cache on a volume claim", { server: { gitCache: { size: "5Gi" } } }],
		["a probe more often than every minute", { sandbox: { runtime: { probeInterval: "30s" } } }],
		["a node selector of numbers", { sandbox: { runtime: { nodeSelector: { pool: 1 } } } }],
		["a backup without its bucket's settings", { postgresql: { backup: { s3: null } } }],
		["every pod of the Gateway namespace", { networkPolicy: { gateway: { podLabels: null } } }],
		["a Gateway parent without a name", { route: { parentRefs: [{ name: "" }] } }],
		["a zero CPU limit", { server: { resources: { limits: { cpu: "0" } } } }],
		["a zero memory request", { server: { resources: { requests: { memory: "0Mi" } } } }],
		[
			"a zero ephemeral-storage limit",
			{ server: { resources: { limits: { "ephemeral-storage": "0.0" } } } },
		],
		["an empty volume", { postgresql: { persistence: { size: "0Gi" } } }],
		["a text assurance switch", { config: { auth: { assuranceEnforced: "false" } } }],
	] as const) {
		assert.equal(validate(merge(valid, change)), false, label);
	}
});

await test("no template grants host or Docker access, or adds a migration owner", () => {
	for (const { name, source: text } of templates) {
		for (const forbidden of [
			/docker\.sock/u,
			/SANDBOX_DOCKER/u,
			/hostPath/u,
			/host(?:Network|PID|IPC):\s*true/u,
			/privileged:\s*true/u,
			/allowPrivilegeEscalation:\s*true/u,
			/^kind:\s*(?:ClusterRole|ClusterRoleBinding)\s*$/mu,
			/helm\.sh\/hook/u,
		]) {
			assert.doesNotMatch(text, forbidden, `${name} matches ${String(forbidden)}`);
		}
		// The backup Jobs run pgBackRest of the PostgreSQL image, the migration Job the server image in
		// its migrate run mode, and the monitor Job psql of the PostgreSQL image; no template runs another Job.
		if (!["postgresql-backup.yaml", "migration.yaml", "postgresql-metrics.yaml"].includes(name)) {
			assert.doesNotMatch(text, /^kind:\s*(?:Job|CronJob)\s*$/mu, name);
		}
		// A secret reaches a container only as a reference to the operator's Secret.
		assert.doesNotMatch(
			text,
			/- name: \w*(?:SECRET|PASSWORD|TOKEN|PRIVATE_KEY|ENCRYPTION_KEY|COOKIE_KEY)\n\s*value:/u,
			name,
		);
	}
	assert.doesNotMatch(source("postgresql-backup.yaml"), /"name" "server"|SPRING_/u);
	// The server is the only role that runs Liquibase.
	assert.doesNotMatch(source("server.yaml"), /SPRING_LIQUIBASE_ENABLED/u);
	assert.match(source("server.yaml"), /SPRING_PROFILES_ACTIVE\n\s*value: prod\n/u);
	// The server receives provider deliveries itself: no role flag splits it.
	assert.doesNotMatch(source("server.yaml"), /HEPHAESTUS_RUNTIME_\w+_ENABLED/u);
	// The server runs the agent runtime exactly when it runs the sandboxes.
	assert.match(
		source("server.yaml"),
		/AGENT_ENABLED\n\s*value: \{\{ \$sandbox\.enabled \| quote \}\}/u,
	);
});

await test("network policy is always rendered", () => {
	assert.equal(at(defaults, ["networkPolicy", "enabled"]), undefined);
	assert.doesNotMatch(source("networkpolicy.yaml"), /networkPolicy\.enabled/u);
	assert.match(source("networkpolicy.yaml"), /^\{\{- \/\* Always rendered\./u);
});

/** The rules of one HTTPRoute document of the route template. */
const rulesOf = (text: string) => text.split(/^ {4}- matches:$/mu).slice(1);

await test("the route sends every path to the server whole, with no filter", () => {
	const [route = "", workspaceRoute = ""] = source("route.yaml").split("---");
	for (const forbidden of [
		/^kind:\s*(?:Gateway|Ingress)\s*$/mu,
		/RequestRedirect/u,
		/certificateRefs/u,
	]) {
		assert.doesNotMatch(route, forbidden, String(forbidden));
	}
	assert.match(route, /fail "route\.parentRefs is required/u);
	const rules = rulesOf(route);
	const rule = (type: string, value: string) => {
		const found = rules.filter((text) =>
			text.includes(`type: ${type}\n            value: ${value}\n`),
		);
		assert.equal(found.length, 1, `${type} ${value}`);
		return found[0] ?? "";
	};
	assert.equal(rules.length, 2);
	assert.match(rule("PathPrefix", "/api"), /request: 0s\n\s*backendRequest: 0s/u);
	// The provider webhooks fall under the root rule, so their paths reach the server whole.
	assert.deepEqual(backendOf(rule("PathPrefix", "/")), ["server", "8080"]);
	const workspaceRules = rulesOf(workspaceRoute);
	assert.equal(workspaceRules.length, 1);
	// The server answers every path and sends every header, HSTS included, so no rule has a filter.
	for (const text of [...rules, ...workspaceRules]) {
		assert.deepEqual(backendOf(text), ["server", "8080"]);
		assert.doesNotMatch(text, /filters:|URLRewrite|ResponseHeaderModifier|hsts/u);
	}
});

await test("the server serves the operator's legal pages from a read-only ConfigMap mount", () => {
	assert.deepEqual(
		templates
			.filter(({ source: text }) => /nginx|"component" "webapp"/u.test(text))
			.map(({ name }) => name),
		[],
	);
	const server = source("server.yaml");
	assert.match(server, /- name: LEGAL_OVERRIDES_DIRECTORY\n\s*value: \/etc\/hephaestus\/legal\n/u);
	assert.match(
		server,
		/- name: legal-overrides\n\s*mountPath: \/etc\/hephaestus\/legal\n\s*readOnly: true\n/u,
	);
	assert.match(server, /- name: legal-overrides\n\s*configMap:\n\s*name: \{\{ \. \| quote \}\}/u);
	assert.match(
		server,
		/- name: SECURITY_CONTACT\n\s*value: \{\{ \.Values\.server\.securityContact \| quote \}\}/u,
	);
});

await test("sandbox identities and other roles do not receive an API token", () => {
	// No ServiceAccount mounts a token by default. Only the server Pod mounts one, and only with sandboxes.
	for (const { name, source: text } of templates) {
		const accounts = text.split(/^---$/mu).filter((part) => /^kind: ServiceAccount$/mu.test(part));
		for (const account of accounts.filter(
			(value) => !value.includes(`"component" "agent-sandbox-controller"`),
		)) {
			assert.match(account, /automountServiceAccountToken: false/u, name);
		}
	}
	assert.match(
		source("server.yaml"),
		/automountServiceAccountToken: \{\{ \$sandbox\.enabled \}\}/u,
	);
});

await test("chart list inputs require arrays of non-empty strings without commas", () => {
	const valid = install(prepared, operator);
	const inputs = [
		["auth", "bootstrapAdmins"],
		["auth", "browserExtensionIds"],
		["auth", "passkeys", "allowedOrigins"],
		["outline", "allowedOrigins"],
	];
	for (const keys of inputs) {
		const change = (value: unknown) => {
			let nested = value;
			for (const key of keys.toReversed()) {
				nested = { [key]: nested };
			}
			return nested;
		};
		for (const accepted of [[], ["first", "second"]]) {
			assert.equal(
				validate(merge(valid, { config: change(accepted) })),
				true,
				JSON.stringify(validate.errors),
			);
		}
		for (const rejected of ["", "first,second", [1], [""], ["first,second"]]) {
			assert.equal(validate(merge(valid, { config: change(rejected) })), false, keys.join("."));
		}
	}
});

function source(name: string): string {
	return templates.find((template) => template.name === name)?.source ?? "";
}

/** The component and port of a route rule's backend, as the template names them. */
function backendOf(text: string) {
	const groups =
		/- name: \{\{ include "hephaestus\.name" \(dict "root" \. "component" "(?<component>\w+)"\) \}\}\n\s*port: (?<port>\d+)/u.exec(
			text,
		)?.groups;
	return groups === undefined ? undefined : [groups.component, groups.port];
}

function at(value: unknown, path: readonly string[]): unknown {
	let current = value;
	for (const key of path) {
		current = isRecord(current) ? current[key] : undefined;
	}
	return current;
}

await test("the sync settings use config.sync without the old monitoring group", () => {
	const valid = install(prepared, operator);
	assert.equal(
		validate(
			merge(valid, {
				config: {
					sync: {
						runOnStartup: false,
						timeframeDays: 14,
						syncCron: "0 0 3 * * *",
						cooldownMinutes: 15,
					},
				},
			}),
		),
		true,
		JSON.stringify(validate.errors),
	);
	assert.equal(validate(merge(valid, { config: { monitoring: { runOnStartup: false } } })), false);
});
