import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	hostAddress,
	hostPorts,
	hostResources,
	hostValues,
	hostWorkerEnvironment,
} from "./dev-kubernetes.ts";
import { asArray, asRecord } from "./lib/json.ts";
import { output } from "./lib/process.ts";

await test("k3d's host address comes from its hosts entry", () => {
	assert.equal(
		hostAddress("127.0.0.1 localhost\n192.0.2.1 host.k3d.internal # k3d\n"),
		"192.0.2.1",
	);
	assert.equal(hostAddress("192.0.2.2 alias host.k3d.internal\n"), "192.0.2.2");
	assert.throws(
		() => hostAddress("192.0.2.1 not-host.k3d.internal\n::1 host.k3d.internal"),
		/no IPv4/u,
	);
});

await test("five host edit loops have distinct namespaces derived from their ports", () => {
	const sets = Array.from({ length: 5 }, (_, index) =>
		hostPorts({
			SERVER_PORT: String(8080 + index * 10),
			SANDBOX_API_PORT: String(8081 + index * 10),
		}),
	);
	assert.equal(new Set(sets.map((set) => set.namespace)).size, 5);
	assert.equal(new Set(sets.flatMap((set) => [set.server, set.gateway, set.management])).size, 15);
	assert.deepEqual(hostPorts({}), {
		namespace: "heph-dev-8080-8081",
		server: 8080,
		gateway: 8081,
		management: 9090,
	});
	assert.throws(() => hostPorts({ SERVER_PORT: "0" }), /SERVER_PORT/u);
	assert.throws(() => hostPorts({ MANAGEMENT_PORT: "8081" }), /must differ/u);
});

await test("host sandboxes retain the chart's quotas and restricted authority without deploying the application", async () => {
	const work = await mkdtemp(path.join(tmpdir(), "heph-dev-test-"));
	const { namespace } = hostPorts({});
	try {
		const values = path.join(work, "values.json");
		await writeFile(values, JSON.stringify(hostValues(namespace, "192.0.2.1")));
		const chart = path.join(import.meta.dirname, "../deploy/chart/hephaestus");
		const rendered = await output("helm", [
			"template",
			"heph",
			chart,
			"--namespace",
			`${namespace}-host`,
			"-f",
			path.join(chart, "profiles/dev.yaml"),
			"-f",
			values,
		]);
		const env = hostWorkerEnvironment(rendered);
		assert.equal(env.AGENT_ENABLED, "true");
		assert.equal(env.SANDBOX_MAX_CONCURRENT, "1");
		assert.equal(env.HEPHAESTUS_AGENT_IMAGE_REQUIRE_DIGEST, "true");
		assert.equal(env.SPRING_PROFILES_ACTIVE, undefined);
		assert.equal(env.HEPHAESTUS_RUNTIME_SERVER_ENABLED, undefined);
		assert.equal(env.SPRING_DATASOURCE_URL, undefined);

		const resources = hostResources(rendered, namespace, "192.0.2.1", 8081).map((value) =>
			asRecord(value, "resource"),
		);
		assert.ok(
			resources.every((resource) =>
				[
					"ServiceAccount",
					"Role",
					"RoleBinding",
					"ResourceQuota",
					"PriorityClass",
					"NetworkPolicy",
				].includes(String(resource.kind)),
			),
		);
		assert.ok(
			resources.every(
				(resource) =>
					resource.kind === "PriorityClass" ||
					asRecord(resource.metadata, "metadata").namespace === namespace,
			),
		);
		const quotas = resources.filter((resource) => resource.kind === "ResourceQuota");
		assert.equal(quotas.length, 3);
		const quota = quotas.find(
			(resource) => asRecord(resource.metadata, "metadata").name === "heph-sandbox-quota",
		);
		assert.ok(quota);
		const hard = asRecord(asRecord(quota.spec, "quota spec").hard, "quota hard");
		assert.equal(hard.secrets, "0");
		assert.equal(hard.persistentvolumeclaims, "0");
		assert.equal(hard.services, "0");
		const binding = resources.find((resource) => resource.kind === "RoleBinding");
		assert.deepEqual(binding?.subjects, [
			{ kind: "ServiceAccount", name: "host-worker", namespace },
		]);
		const policies = resources.filter((resource) => resource.kind === "NetworkPolicy");
		assert.equal(policies.length, 3);
		const deny = policies.find((resource) => {
			const spec = asRecord(resource.spec, "policy");
			return spec.egress === undefined && spec.ingress === undefined;
		});
		assert.ok(deny);
		assert.deepEqual(deny.spec, { podSelector: {}, policyTypes: ["Ingress", "Egress"] });
		// The tools Jobs admit only ingress; the deny-all policy still denies their egress.
		const tools = policies.find(
			(resource) => asRecord(resource.spec, "policy").ingress !== undefined,
		);
		assert.ok(tools);
		assert.deepEqual(asRecord(tools.spec, "policy").policyTypes, ["Ingress"]);
		const gateway = policies.find(
			(resource) => asRecord(resource.spec, "policy").egress !== undefined,
		);
		assert.ok(gateway);
		assert.deepEqual(asRecord(gateway.spec, "policy").egress, [
			{ to: [{ ipBlock: { cidr: "192.0.2.1/32" } }], ports: [{ protocol: "TCP", port: 8081 }] },
		]);
		const role = resources.find((resource) => resource.kind === "Role");
		assert.ok(role);
		assert.ok(
			asArray(role.rules, "rules").every(
				(rule) => !asArray(asRecord(rule, "rule").resources, "resources").includes("secrets"),
			),
		);
		assert.throws(() => hostResources(rendered, namespace, "host.k3d.internal", 8081), /IPv4/u);
		assert.throws(() => hostResources("", namespace, "192.0.2.1", 8081), /expected sandbox/u);
	} finally {
		await rm(work, { recursive: true, force: true });
	}
});
