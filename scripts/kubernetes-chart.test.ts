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
			merge(
				values,
				merge(operator, { images: { webapp: "ghcr.io/hephaestus-build/webapp:1.2.3" } }),
			),
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
			chartValues(lockWith(rawLock.images.filter((image) => image.image !== "nats")), inventory),
		/missing: nats; extra: none/u,
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
						image.image === "webapp" ? { ...image, provenance: "upstream" } : image,
					),
				),
				inventory,
			),
		/webapp has the wrong provenance class/u,
	);
	assert.throws(
		() =>
			chartValues(
				lockWith(
					rawLock.images.map((image) =>
						image.image === "nats" ? { ...image, repository: "docker.io/library/nats" } : image,
					),
				),
				inventory,
			),
		/nats is locked from docker\.io\/library\/nats/u,
	);
	assert.throws(
		() =>
			chartValues(
				lockWith(
					rawLock.images.map((image) =>
						image.image === "nats" ? { ...image, indexDigest: digest("e") } : image,
					),
				),
				inventory,
			),
		/nats is locked at sha256:e{64}, not the inventory pin/u,
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
		HEPHAESTUS_IMAGE_AGENT_PI: `ghcr.io/hephaestus-build/agent-pi@${digest("1")}`,
		HEPHAESTUS_IMAGE_ALERTMANAGER: pin("alertmanager"),
		HEPHAESTUS_IMAGE_APPLICATION_SERVER: `ghcr.io/hephaestus-build/application-server@${digest("2")}`,
		HEPHAESTUS_IMAGE_NATS: pin("nats"),
		HEPHAESTUS_IMAGE_VICTORIA_METRICS: pin("victoria-metrics"),
		HEPHAESTUS_IMAGE_VMALERT: pin("vmalert"),
		HEPHAESTUS_IMAGE_POSTGRES: `ghcr.io/hephaestus-build/postgres@${digest("4")}`,
		HEPHAESTUS_IMAGE_POSTGRES_EXPORTER: pin("postgres-exporter"),
		HEPHAESTUS_IMAGE_WEBAPP: `ghcr.io/hephaestus-build/webapp@${digest("5")}`,
		// An image the chart does not run.
		HEPHAESTUS_IMAGE_TRAEFIK: `docker.io/library/traefik@${digest("6")}`,
	};
	const values = commitChartValues(images, inventory, commit);
	assert.deepEqual(Object.keys(values.images), [...chartImages]);
	assert.equal(values.images.postgres, images.HEPHAESTUS_IMAGE_POSTGRES);
	// A followed commit reports the commit as its version, which the promotion verifies.
	assert.equal(values.release.version, commit);

	const { HEPHAESTUS_IMAGE_WEBAPP: _webapp, ...withoutWebapp } = images;
	assert.throws(() => commitChartValues(withoutWebapp, inventory, commit), /no webapp digest/u);
	assert.throws(
		() =>
			commitChartValues(
				{
					...images,
					HEPHAESTUS_IMAGE_NATS: pin("nats").replace(/@sha256:[0-9a-f]+$/u, `@${digest("e")}`),
				},
				inventory,
				commit,
			),
		/nats is locked at .*, not the inventory pin/u,
	);
	assert.throws(
		() =>
			commitChartValues(
				{ ...images, HEPHAESTUS_IMAGE_WEBAPP: "ghcr.io/hephaestus-build/webapp:main" },
				inventory,
				commit,
			),
		/no webapp digest/u,
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
		"ghcr.io/hephaestus-build/webapp:latest",
		"ghcr.io/hephaestus-build/webapp:1.2.3",
		`registry.example.com/webapp@${digest("1")}`,
		"ghcr.io/hephaestus-build/webapp@sha256:short",
	]) {
		const values = install(prepared, operator, { images: { webapp: reference } });
		assert.equal(validate(values), false, reference);
	}
});

const load = (name: string) => asRecord(parse(read(`profiles/${name}`)), name);

await test("every profile installs from the same templates with only the operator's values", () => {
	assert.deepEqual(profiles.toSorted(), ["cluster.yaml", "dev.yaml", "preview.yaml", "small.yaml"]);
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
					/^```yaml title="values\.yaml"\n(?<block>.*?)^```$/gmsu,
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
	// Each database role runs at most its bound, and zero stops it for maintenance.
	for (const [role, bound, fallback] of [
		["server", 1, 1],
		["webhook", 3, 1],
	] as const) {
		assert.equal(at(defaults, [role, "replicas"]), fallback, `${role} default`);
		for (const replicas of [0, bound]) {
			const change = { [role]: { replicas } };
			assert.equal(validate(merge(valid, change)), true, `${role} at ${replicas}`);
		}
	}
	for (const [label, change] of [
		["a generic environment hatch", { server: { extraEnv: [{ name: "X", value: "y" }] } }],
		["a pod spec hatch", { server: { podSpec: { hostNetwork: true } } }],
		["a second server replica", { server: { replicas: 2 } }],
		["a negative server count", { server: { replicas: -1 } }],
		["a fourth webhook receiver", { webhook: { replicas: 4 } }],
		["a negative webhook count", { webhook: { replicas: -1 } }],
		["a fractional webhook count", { webhook: { replicas: 0.5 } }],
		["the retired worker role", { worker: { replicas: 1 } }],
		["an inline secret value", { secret: { name: "hephaestus", WEBHOOK_SECRET: "x" } }],
		["an sslmode the chart does not offer", { postgresql: { external: { sslmode: "allow" } } }],
		["a broker budget below one GiB", { nats: { jetstream: { maxFileBytes: 1024 } } }],
		["network policy turned off", { networkPolicy: { enabled: false } }],
		["no Gateway to attach to", { route: { parentRefs: [] } }],
		["a Gateway the chart creates", { route: { gateway: { create: true } } }],
		["a server rollout of an unknown kind", { server: { rolloutStrategy: "BlueGreen" } }],
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
		["a zero memory request", { webhook: { resources: { requests: { memory: "0Mi" } } } }],
		[
			"a zero ephemeral-storage limit",
			{ webapp: { resources: { limits: { "ephemeral-storage": "0.0" } } } },
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
	assert.doesNotMatch(source("postgresql-backup.yaml"), /application-server|SPRING_/u);
	// The server is the only role that runs Liquibase.
	assert.doesNotMatch(source("server.yaml"), /SPRING_LIQUIBASE_ENABLED/u);
	assert.match(source("webhook.yaml"), /SPRING_LIQUIBASE_ENABLED\n\s*value: "false"/u);
	assert.match(source("server.yaml"), /SPRING_PROFILES_ACTIVE\n\s*value: prod\n/u);
	// Roles stay split: the server never receives webhooks, and the receiver never serves the API or runs reviews.
	assert.match(source("server.yaml"), /HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED\n\s*value: "false"/u);
	assert.match(source("webhook.yaml"), /HEPHAESTUS_RUNTIME_SERVER_ENABLED\n\s*value: "false"/u);
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

await test("the route strips only /api and keeps every webhook path whole", () => {
	const route = source("route.yaml").split("---")[0] ?? "";
	for (const forbidden of [
		/^kind:\s*(?:Gateway|Ingress)\s*$/mu,
		/RequestRedirect/u,
		/certificateRefs/u,
	]) {
		assert.doesNotMatch(route, forbidden, String(forbidden));
	}
	assert.match(route, /fail "route\.parentRefs is required/u);
	const rules = route.split(/^ {4}- matches:$/mu).slice(1);
	const rule = (type: string, value: string) => {
		const found = rules.filter((text) =>
			text.includes(`type: ${type}\n            value: ${value}\n`),
		);
		assert.equal(found.length, 1, `${type} ${value}`);
		return found[0] ?? "";
	};
	assert.deepEqual(backendOf(rule("Exact", "/webhooks/slack/interactivity")), ["server", "8080"]);
	assert.deepEqual(backendOf(rule("PathPrefix", "/webhooks")), ["webhook", "8080"]);
	assert.deepEqual(backendOf(rule("PathPrefix", "/api")), ["server", "8080"]);
	assert.deepEqual(backendOf(rule("PathPrefix", "/")), ["webapp", "8080"]);
	assert.match(
		rule("PathPrefix", "/api"),
		/type: URLRewrite\n\s*urlRewrite:\n\s*path:\n\s*type: ReplacePrefixMatch\n\s*replacePrefixMatch: \/\n/u,
	);
	for (const [type, value] of [
		["Exact", "/webhooks/slack/interactivity"],
		["PathPrefix", "/webhooks"],
		["PathPrefix", "/"],
	] as const) {
		assert.doesNotMatch(rule(type, value), /URLRewrite/u, value);
	}
	// HSTS has nowhere else to come from, so every rule carries it.
	assert.equal(rules.filter((text) => text.includes("{{ toJson $hsts }}")).length, rules.length);
});

await test("the webapp runs the non-root image as it is, with only /tmp writable", () => {
	const webapp = source("webapp.yaml");
	for (const forbidden of [
		/initContainers/u,
		/sysctls/u,
		/ip_unprivileged_port_start/u,
		// The operator's legal pages are the one mount there, and it is read-only.
		/\/usr\/share\/nginx\/html(?!\/legal-overrides\n\s*readOnly: true\n)/u,
		/\/var\/cache\/nginx/u,
		/mountPath: \/run/u,
		/^\s*(?:command|args):/mu,
		/port: 80\n/u,
		/containerPort: 80\n/u,
	]) {
		assert.doesNotMatch(webapp, forbidden, String(forbidden));
	}
	assert.match(webapp, /"user" 101 "group" 101/u);
	assert.match(webapp, /containerPort: 8080\n/u);
	assert.match(webapp, /- name: http\n\s*port: 8080\n\s*targetPort: http/u);
	assert.deepEqual(
		[...webapp.matchAll(/mountPath: (?<path>\S+)/gu)].map((match) => match.groups?.path),
		[
			"/etc/nginx/conf.d/workspace-maps.conf",
			"/etc/nginx/workspace/server.conf",
			"/tmp/hephaestus-webapp",
			"/usr/share/nginx/html/legal-overrides",
		],
	);
	assert.match(webapp, /emptyDir:\n\s*sizeLimit: 16Mi/u);
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

await test("stream byte overrides are keyed by a supported provider without the old GitHub field", () => {
	const valid = install(prepared, operator);
	assert.equal(
		validate(
			merge(valid, {
				webhook: {
					stream: {
						maxBytesByStream: {
							github: "10GB",
							gitlab: "2GB",
							slack: "512MB",
							outline: "256MB",
						},
					},
				},
			}),
		),
		true,
		JSON.stringify(validate.errors),
	);
	assert.equal(validate(merge(valid, { webhook: { stream: { maxBytesGithub: "1GB" } } })), false);
	assert.equal(
		validate(merge(valid, { webhook: { stream: { maxBytesByStream: { other: "1GB" } } } })),
		false,
	);
	assert.equal(
		validate(merge(valid, { webhook: { stream: { maxBytesByStream: { gitlab: "-1GB" } } } })),
		false,
	);
});
