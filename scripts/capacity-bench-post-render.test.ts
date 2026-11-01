import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { parse, parseAllDocuments, stringify } from "yaml";

import { asArray, asRecord, isRecord, parseJson } from "./lib/json.ts";

const namespace = "capacity-bench-20261030";
const deployment = {
	apiVersion: "apps/v1",
	kind: "Deployment",
	metadata: { name: "bench-server", namespace },
	spec: {
		template: {
			metadata: { labels: { "app.kubernetes.io/component": "server" } },
			spec: {
				containers: [
					{
						name: "server",
						image: "synthetic-fixture",
						env: [{ name: "SPRING_PROFILES_ACTIVE", value: "prod" }],
						resources: { limits: { memory: "2560Mi" } },
					},
				],
			},
		},
	},
};

function render(resources: unknown[], target = namespace, addClass = true) {
	const input = [...resources];
	if (
		addClass &&
		!input.some((resource) => asRecord(resource, "resource").kind === "PriorityClass")
	) {
		input.push({
			kind: "PriorityClass",
			metadata: { name: `${namespace}-bench-roles` },
			value: 100_000,
		});
	}
	return spawnSync(process.execPath, ["scripts/capacity-bench/post-render.ts"], {
		input: input.map((resource) => `---\n${stringify(resource)}`).join(""),
		encoding: "utf8",
		env: { ...process.env, HELM_NAMESPACE: target, BENCH_KUBERNETES_API_CIDR: "192.0.2.10/32" },
	});
}

await test("the test overlay retains role resources and database settings while isolating auth", () => {
	const result = render([deployment]);
	assert.equal(result.status, 0, result.stderr);
	const resource = asRecord(parseAllDocuments(result.stdout)[0]?.toJSON(), "deployment");
	const template = asRecord(asRecord(resource.spec, "spec").template, "template");
	const containers = asArray(asRecord(template.spec, "pod").containers, "containers").map((row) =>
		asRecord(row, "container"),
	);
	const server = asRecord(containers[0], "server");
	assert.deepEqual(server.resources, deployment.spec.template.spec.containers[0]?.resources);
	assert.equal(server.image, "synthetic-fixture");
	assert.equal(containers[1]?.name, "driver");
	const env = asArray(server.env, "env").map((row) => asRecord(row, "env"));
	assert.equal(env.find((row) => row.name === "SPRING_PROFILES_ACTIVE")?.value, "e2e");
	const serialized = env.find((row) => row.name === "SPRING_APPLICATION_JSON")?.value;
	assert.equal(typeof serialized, "string");
	const explicit = asRecord(parseJson(String(serialized)), "prod properties");
	const spring = asRecord(explicit.spring, "spring");
	assert.deepEqual(spring.liquibase, { contexts: "prod", enabled: false });
	assert.equal(asRecord(spring.datasource, "datasource").url, `jdbc:\${DATABASE_URL}`);
});

await test("only the server role keeps the production NATS consumer on", () => {
	const worker = structuredClone(deployment);
	worker.metadata.name = "bench-worker";
	worker.spec.template.metadata.labels["app.kubernetes.io/component"] = "worker";
	const result = render([deployment, worker]);
	assert.equal(result.status, 0, result.stderr);
	const natsEnabled = (index: number) => {
		const resource = asRecord(parseAllDocuments(result.stdout)[index]?.toJSON(), "deployment");
		const pod = asRecord(
			asRecord(asRecord(resource.spec, "spec").template, "template").spec,
			"pod",
		);
		const env = asArray(asRecord(asArray(pod.containers, "containers")[0], "container").env, "env");
		const json = env
			.map((row) => asRecord(row, "env"))
			.find((row) => row.name === "SPRING_APPLICATION_JSON");
		const explicit = asRecord(parseJson(String(json?.value)), "properties");
		const sync = asRecord(asRecord(explicit.hephaestus, "hephaestus").sync, "sync");
		return asRecord(sync.nats, "nats").enabled;
	};
	assert.equal(natsEnabled(0), true);
	assert.equal(natsEnabled(1), false);
});

await test("the overlay removes public routes and Internet egress but keeps the database path", () => {
	const route = {
		apiVersion: "gateway.networking.k8s.io/v1",
		kind: "HTTPRoute",
		metadata: { name: "bench", namespace },
	};
	const policy = {
		apiVersion: "networking.k8s.io/v1",
		kind: "NetworkPolicy",
		metadata: { name: "bench", namespace },
		spec: {
			podSelector: {},
			policyTypes: ["Egress"],
			egress: [
				{ to: [{ ipBlock: { cidr: "0.0.0.0/0" } }], ports: [{ port: 443 }] },
				{ to: [{ podSelector: { matchLabels: { database: "bench" } } }], ports: [{ port: 5432 }] },
			],
		},
	};
	const result = render([route, policy]);
	assert.equal(result.status, 0, result.stderr);
	const resources = parseAllDocuments(result.stdout)
		.map((document) => asRecord(document.toJSON(), "resource"))
		.filter((resource) => resource.kind !== "PriorityClass");
	assert.equal(resources.length, 1);
	assert.deepEqual(asRecord(resources[0]?.spec, "spec").egress, [policy.spec.egress[1]]);
});

await test("the renderer refuses another Helm namespace or an object outside the lab", () => {
	assert.notEqual(render([deployment], "default").status, 0);
	assert.notEqual(
		render([{ ...deployment, metadata: { ...deployment.metadata, namespace: "default" } }]).status,
		0,
	);
	assert.notEqual(render([deployment], namespace, false).status, 0);
	assert.notEqual(
		render([
			{ kind: "PriorityClass", metadata: { name: "hephaestus-bench-roles" }, value: 100_000 },
		]).status,
		0,
	);
});

await test("benchmark classes cannot preempt another release", () => {
	for (const tier of ["roles", "conversations", "reviews"]) {
		const result = render([
			{
				apiVersion: "scheduling.k8s.io/v1",
				kind: "PriorityClass",
				metadata: { name: `${namespace}-bench-${tier}` },
				value: 100_000,
				globalDefault: true,
			},
		]);
		assert.equal(result.status, 0, result.stderr);
		const resource = asRecord(parseAllDocuments(result.stdout)[0]?.toJSON(), "class");
		assert.equal(resource.preemptionPolicy, "Never");
		assert.equal(resource.globalDefault, false);
		assert.ok(typeof resource.value === "number" && resource.value < 1000);
	}
	assert.notEqual(
		render([{ kind: "PriorityClass", metadata: { name: "shared-roles" }, value: 100_000 }]).status,
		0,
	);
});

await test("policy rules fail closed for missing peers, IPv6 Internet and non-DNS system pods", () => {
	const rules = [
		{},
		{ to: [] },
		{ to: [{}] },
		{ to: [{ ipBlock: { cidr: "::/0" } }] },
		{
			to: [
				{ namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "kube-system" } } },
			],
		},
		{ to: [{ ipBlock: { cidr: "192.0.2.10/32" } }], ports: [{ port: 80 }] },
	];
	const result = render([
		{ kind: "NetworkPolicy", metadata: { name: "bench", namespace }, spec: { egress: rules } },
	]);
	assert.equal(result.status, 0, result.stderr);
	assert.deepEqual(
		asRecord(asRecord(parseAllDocuments(result.stdout)[0]?.toJSON(), "policy").spec, "spec").egress,
		[],
	);
});

function leaves(value: unknown, prefix = ""): Map<string, unknown> {
	if (!isRecord(value)) {
		return new Map([[prefix, value]]);
	}
	return new Map(
		Object.entries(value).flatMap(([key, child]) => [
			...leaves(child, prefix === "" ? key : `${prefix}.${key}`),
		]),
	);
}

await test("every e2e property has a production value or an explicit auth exception", () => {
	const root = "server/application/src/main/resources/";
	const e2e = leaves(parse(readFileSync(`${root}application-e2e.yml`, "utf8")));
	const base = leaves(
		parseAllDocuments(readFileSync(`${root}application.yml`, "utf8"))[0]?.toJSON(),
	);
	const production = leaves(parse(readFileSync(`${root}application-prod.yml`, "utf8")));
	const result = render([deployment]);
	assert.equal(result.status, 0, result.stderr);
	const resource = asRecord(parseAllDocuments(result.stdout)[0]?.toJSON(), "deployment");
	const pod = asRecord(asRecord(asRecord(resource.spec, "spec").template, "template").spec, "pod");
	const env = asArray(asRecord(asArray(pod.containers, "containers")[0], "server").env, "env").map(
		(row) => asRecord(row, "variable"),
	);
	const explicit = leaves(
		parseJson(String(env.find((row) => row.name === "SPRING_APPLICATION_JSON")?.value)),
	);
	const exceptions = new Set([
		"hephaestus.auth.dev-login-enabled",
		"hephaestus.auth.cookie-secure",
		"hephaestus.auth.cookie-name",
	]);
	const defaults = new Map<string, unknown>([
		["hephaestus.workspace.creation-policy", "ADMIN_ONLY"],
		["hephaestus.security.encryption-key", `\${HEPHAESTUS_SECURITY_ENCRYPTION_KEY}`],
		[
			"hephaestus.security.credential-encryption-key",
			`\${HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY}`,
		],
		["hephaestus.sync.filters.allowed-organizations", []],
		["hephaestus.sync.filters.allowed-repositories", []],
	]);
	for (const [key, value] of e2e) {
		if (exceptions.has(key)) {
			continue;
		}
		const expected = production.get(key) ?? base.get(key) ?? defaults.get(key);
		assert.notEqual(expected, undefined, `Unreviewed e2e property: ${key}`);
		assert.deepEqual(
			explicit.has(key) ? explicit.get(key) : value,
			expected,
			`Production parity: ${key}`,
		);
	}
});
