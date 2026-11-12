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
	hostServerEnvironment,
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

const IMAGE = `ghcr.io/hephaestus-build/agent-pi@sha256:${"1".repeat(64)}`;

await test("host development applies sandbox templates and pools without an application loop", async () => {
	const work = await mkdtemp(path.join(tmpdir(), "heph-dev-test-"));
	try {
		const namespace = "heph-dev-8090-8091";
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
		const env = hostServerEnvironment(rendered);
		assert.equal(env.AGENT_ENABLED, "true");
		assert.equal(env.SANDBOX_MAX_CONCURRENT, "1");
		assert.equal(env.HEPHAESTUS_CONVERSATION_LOOP_SECRET, undefined);
		assert.equal(env.SPRING_DATASOURCE_URL, undefined);
		const host = { address: "192.0.2.1", port: 8091, image: IMAGE };
		const resources = hostResources(rendered, namespace, host).map((value) =>
			asRecord(value, "resource"),
		);
		const templates = resources.filter((resource) => resource.kind === "SandboxTemplate");
		const pools = resources.filter((resource) => resource.kind === "SandboxWarmPool");
		assert.equal(templates.length, 2);
		assert.equal(pools.length, 2);
		assert.ok(
			resources.every(
				(resource) =>
					resource.kind !== "Deployment" && resource.kind !== "Secret" && resource.kind !== "Job",
			),
		);
		for (const template of templates) {
			const spec = asRecord(template.spec, "template");
			const pod = asRecord(asRecord(spec.podTemplate, "Pod template").spec, "Pod spec");
			const containers = asArray(pod.containers, "containers").map((value) =>
				asRecord(value, "container"),
			);
			assert.equal(containers.length, 1);
			assert.equal(containers[0]?.image, IMAGE);
			assert.equal(pod.automountServiceAccountToken, false);
			assert.equal(asRecord(spec, "template").networkPolicyManagement, "Unmanaged");
		}
		const gateway = resources.find(
			(resource) =>
				resource.kind === "NetworkPolicy" &&
				asRecord(resource.metadata, "metadata").name === "heph-sandbox-gateway",
		);
		assert.ok(gateway);
		assert.deepEqual(asRecord(gateway.spec, "policy").egress, [
			{ to: [{ ipBlock: { cidr: "192.0.2.1/32" } }], ports: [{ protocol: "TCP", port: 8091 }] },
		]);
		const binding = resources.find((resource) => resource.kind === "RoleBinding");
		assert.ok(binding);
		assert.deepEqual(binding.subjects, [
			{ kind: "ServiceAccount", name: "host-server", namespace },
		]);
		const role = resources.find((resource) => resource.kind === "Role");
		assert.ok(role);
		assert.deepEqual(
			asArray(role.rules, "rules").map((rule) => asRecord(rule, "rule").resources),
			[["sandboxclaims"]],
		);
		assert.throws(
			() => hostResources(rendered, namespace, { ...host, address: "host.k3d.internal" }),
			/IPv4/u,
		);
		assert.throws(() => hostResources("", namespace, host), /expected sandbox/u);
	} finally {
		await rm(work, { recursive: true, force: true });
	}
});
