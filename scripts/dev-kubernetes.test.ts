import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	conversationLoopSecret,
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

await test("each port set makes its loop secret once and keeps it private", async () => {
	const work = await mkdtemp(path.join(tmpdir(), "heph-dev-test-"));
	try {
		const file = path.join(work, "conversation-loop-secret");
		const secret = await conversationLoopSecret(file);
		assert.match(secret, /^[0-9a-f]{64}$/u);
		assert.equal(await conversationLoopSecret(file), secret);
		// Windows keeps no POSIX mode bits for the check to read.
		if (process.platform !== "win32") {
			const { mode } = await stat(file);
			assert.equal(mode % 0o1000, 0o600);
		}
		await writeFile(file, "short");
		await assert.rejects(conversationLoopSecret(file), /64 lowercase hex/u);
		assert.equal(await readFile(file, "utf8"), "short");
	} finally {
		await rm(work, { recursive: true, force: true });
	}
});

const IMAGE = `ghcr.io/hephaestus-build/agent-pi@sha256:${"1".repeat(64)}`;
const LOOP_SECRET = "a".repeat(64);

await test("host sandboxes keep the chart's quotas and authority, and only the conversation loop of the application runs in k3d", async () => {
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
		assert.equal(env.HEPHAESTUS_MENTOR_TURN_CAPACITY, "1");
		assert.equal(env.HEPHAESTUS_AGENT_IMAGE_REQUIRE_DIGEST, "true");
		assert.equal(env.SPRING_PROFILES_ACTIVE, undefined);
		assert.equal(env.HEPHAESTUS_RUNTIME_SERVER_ENABLED, undefined);
		assert.equal(env.SPRING_DATASOURCE_URL, undefined);

		const host = { address: "192.0.2.1", port: 8091, image: IMAGE, loopSecret: LOOP_SECRET };
		const resources = hostResources(rendered, namespace, host).map((value) =>
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
					"Secret",
					"Deployment",
				].includes(String(resource.kind)),
			),
		);
		const named = (kind: string, name: string) =>
			resources.find(
				(resource) =>
					resource.kind === kind && asRecord(resource.metadata, "metadata").name === name,
			);
		// Only the loop, its credential and its policy are in the release namespace.
		assert.deepEqual(
			resources
				.filter(
					(resource) =>
						resource.kind !== "PriorityClass" &&
						asRecord(resource.metadata, "metadata").namespace !== namespace,
				)
				.map((resource) => [
					resource.kind,
					asRecord(resource.metadata, "metadata").name,
					asRecord(resource.metadata, "metadata").namespace,
				]),
			[
				["Secret", "heph-app", `${namespace}-host`],
				["NetworkPolicy", "heph-conversation-loop", `${namespace}-host`],
				["ServiceAccount", "heph-conversation-loop", `${namespace}-host`],
				["Deployment", "heph-conversation-loop", `${namespace}-host`],
			],
		);
		assert.deepEqual(
			resources
				.filter((resource) => resource.kind === "PriorityClass")
				.map((resource) => String(asRecord(resource.metadata, "metadata").name))
				.toSorted(),
			[
				`${namespace}-host-heph-conversations`,
				`${namespace}-host-heph-reviews`,
				`${namespace}-host-heph-roles`,
			],
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
		assert.deepEqual(
			resources
				.filter((resource) => resource.kind === "NetworkPolicy")
				.map((resource) => String(asRecord(resource.metadata, "metadata").name))
				.toSorted(),
			["heph-conversation-loop", "heph-sandbox-deny", "heph-sandbox-gateway", "heph-sandbox-tools"],
		);
		const policy = (name: string) => asRecord(named("NetworkPolicy", name)?.spec, name);
		assert.deepEqual(policy("heph-sandbox-deny"), {
			podSelector: {},
			policyTypes: ["Ingress", "Egress"],
		});
		const gateway = {
			to: [{ ipBlock: { cidr: "192.0.2.1/32" } }],
			ports: [{ protocol: "TCP", port: 8091 }],
		};
		const loopPod = {
			"app.kubernetes.io/name": "hephaestus",
			"app.kubernetes.io/instance": "heph",
			"app.kubernetes.io/component": "conversation-loop",
		};
		assert.deepEqual(policy("heph-sandbox-gateway").egress, [gateway]);
		// The loop reaches the host's gateway and the tools port of tools Pods, and nothing else.
		assert.deepEqual(policy("heph-conversation-loop"), {
			podSelector: { matchLabels: loopPod },
			policyTypes: ["Ingress", "Egress"],
			egress: [
				gateway,
				{
					to: [
						{
							namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": namespace } },
							podSelector: { matchLabels: { "hephaestus.build/sandbox-kind": "tools" } },
						},
					],
					ports: [{ port: 7100, protocol: "TCP" }],
				},
			],
		});
		// Tools Pods admit the loop where it runs, and only on the tools port.
		assert.deepEqual(policy("heph-sandbox-tools"), {
			podSelector: { matchLabels: { "hephaestus.build/sandbox-kind": "tools" } },
			policyTypes: ["Ingress"],
			ingress: [
				{
					from: [
						{
							namespaceSelector: {
								matchLabels: { "kubernetes.io/metadata.name": `${namespace}-host` },
							},
							podSelector: { matchLabels: loopPod },
						},
					],
					ports: [{ port: 7100, protocol: "TCP" }],
				},
			],
		});
		const loop = named("Deployment", "heph-conversation-loop");
		assert.ok(loop);
		const loopSpec = asRecord(loop.spec, "loop");
		assert.equal(loopSpec.replicas, 1);
		const template = asRecord(loopSpec.template, "loop template");
		assert.deepEqual(asRecord(template.metadata, "loop metadata").labels, {
			...loopPod,
			"app.kubernetes.io/managed-by": "Helm",
			"app.kubernetes.io/part-of": "hephaestus",
		});
		const container = asRecord(
			asArray(asRecord(template.spec, "loop Pod").containers, "containers")[0],
			"loop container",
		);
		assert.equal(container.image, IMAGE);
		const loopEnv = new Map(
			asArray(container.env, "loop environment").map((entry) => {
				const variable = asRecord(entry, "variable");
				return [variable.name, variable];
			}),
		);
		assert.equal(loopEnv.get("LLM_PROXY_URL")?.value, "http://192.0.2.1:8091/internal/llm");
		assert.equal(loopEnv.get("HEPHAESTUS_MENTOR_TURN_CAPACITY")?.value, "1");
		assert.deepEqual(loopEnv.get("HEPHAESTUS_CONVERSATION_LOOP_SECRET")?.valueFrom, {
			secretKeyRef: { name: "heph-app", key: "HEPHAESTUS_CONVERSATION_LOOP_SECRET" },
		});
		assert.deepEqual(named("Secret", "heph-app")?.data, {
			HEPHAESTUS_CONVERSATION_LOOP_SECRET: Buffer.from(LOOP_SECRET).toString("base64"),
		});
		const role = resources.find((resource) => resource.kind === "Role");
		assert.ok(role);
		assert.ok(
			asArray(role.rules, "rules").every(
				(rule) => !asArray(asRecord(rule, "rule").resources, "resources").includes("secrets"),
			),
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
