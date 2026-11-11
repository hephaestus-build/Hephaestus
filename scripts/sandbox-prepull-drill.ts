/**
 * Proves on a disposable CI node that a sandbox never waits for the agent image: the release's
 * agent-image DaemonSet pulls it as the node joins, and a sandbox that the node then starts finds the
 * image there (docs/admin/kubernetes.mdx § Sandbox templates).
 *
 * It adds a node to the k3d cluster, waits for the DaemonSet's Pod on it, and claims one sandbox from a
 * copy of the review template that only waits and runs on that node. The kubelet's events must show
 * that it pulled the image for the DaemonSet and found it present for the sandbox. The step summary
 * records both times.
 *
 *   node scripts/sandbox-prepull-drill.ts [--gvisor <directory of `kubernetes-cluster.ts gvisor`>]
 *
 * With --gvisor, the node gets the one-host platform's runsc and containerd template before its
 * kubelet starts Pods, as the cluster's other nodes have them.
 */
import assert from "node:assert/strict";
import { appendFile } from "node:fs/promises";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { NAMESPACE, RELEASE, SANDBOXES } from "./kubernetes-cluster.ts";
import { asArray, asRecord, asString, at, parseJson } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";
import { gateTemplate } from "./sandbox-isolation.ts";

const CLUSTER = "hephaestus";
const CONTAINERD_TEMPLATE = path.join(
	import.meta.dirname,
	"..",
	"deploy",
	"platform",
	"k3s-containerd.toml.tmpl",
);

const kubectl = async (args: string[], input?: string) => output("kubectl", args, { input });
const json = async (args: string[]) => parseJson(await kubectl([...args, "-o", "json"]));

/** A kubelet image event of one Pod: what it did with the image, and its own words. */
export interface ImageEvent {
	reason: string;
	message: string;
}

/** The image events of the Pod `name` in the event list, oldest first. */
export function imageEvents(events: unknown, name: string): ImageEvent[] {
	return asArray(asRecord(events, "events").items, "events.items")
		.map((event) => asRecord(event, "event"))
		.filter(
			(event) =>
				at(event, ["involvedObject", "name"], "event") === name &&
				["Pulling", "Pulled"].includes(String(event.reason)),
		)
		.map((event) => ({ reason: String(event.reason), message: String(event.message) }));
}

/** Whether the kubelet started the Pod from an image that was on the node already. */
export function foundPresent(events: ImageEvent[]): boolean {
	return (
		events.length > 0 &&
		events.every(
			(event) => event.reason === "Pulled" && /already present on machine/u.test(event.message),
		)
	);
}

async function waitFor(
	what: string,
	check: () => Promise<boolean>,
	timeoutMs: number,
): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (!(await check())) {
		if (Date.now() > deadline) {
			throw new Error(`${what} did not happen within ${timeoutMs / 1000} s`);
		}
		await sleep(100);
	}
}

/** Waits until the node is Ready; otherwise prints the end of its k3s log and fails. */
async function nodeReady(node: string): Promise<void> {
	try {
		await waitFor(
			`node ${node} becoming ready`,
			async () => {
				const listed = await output("kubectl", [
					"get",
					"node",
					node,
					"--ignore-not-found",
					"-o",
					'jsonpath={.status.conditions[?(@.type=="Ready")].status}',
				]);
				return listed.trim() === "True";
			},
			180_000,
		);
	} catch (error) {
		// The end of the node's k3s log names why, in this job's log.
		await run("docker", ["logs", "--tail", "80", node]).catch(() => undefined);
		throw new Error(`${node} did not become ready; the k3s log above names why`, { cause: error });
	}
}

async function main(): Promise<void> {
	const { values } = parseArgs({ options: { gvisor: { type: "string" } } });
	assert.equal(
		process.env.CI,
		"true",
		"the drill adds a node, so it runs only on a disposable CI cluster",
	);
	const context = await output("kubectl", ["config", "current-context"]);
	assert.equal(context.trim(), `k3d-${CLUSTER}`);
	const nodeName = `${CLUSTER}-prepull-${process.pid}`;
	const node = `k3d-${nodeName}-0`;
	const pool = `prepull-drill-${process.pid}`;
	const claim = `prepull-drill-${process.pid}`;
	let failure: Error | undefined;
	try {
		// k3d's own wait deletes a node that fails to register, with its logs; this wait keeps them.
		await run("k3d", ["node", "create", nodeName, "--cluster", CLUSTER, "--role", "agent"]);
		await nodeReady(node);
		if (values.gvisor !== undefined) {
			const { gvisor } = values;
			// `k3d node create --cluster` copies an existing node's settings, so a cluster created with the files as
			// `--volume …@all` mounts them on the new node too. A copy onto a mounted file fails (the target is busy):
			// copy only what the node does not mount.
			const mounts = await output("docker", [
				"inspect",
				"-f",
				"{{range .Mounts}}{{println .Destination}}{{end}}",
				node,
			]);
			const mounted = new Set(mounts.split("\n").map((line) => line.trim()));
			const files: [string, string][] = [
				...["runsc", "containerd-shim-runsc-v1", "gvisor-bin"].map((file): [string, string] => [
					path.join(gvisor, file),
					`/usr/local/bin/${file}`,
				]),
				[CONTAINERD_TEMPLATE, "/var/lib/rancher/k3s/agent/etc/containerd/config-v3.toml.tmpl"],
			];
			const missing = files.filter(([, target]) => !mounted.has(target));
			for (const [source, target] of missing) {
				await run("docker", ["cp", source, `${node}:${target}`]);
			}
			if (missing.length > 0) {
				// k3s writes containerd's configuration from the template when it starts.
				await run("docker", ["restart", node]);
				await nodeReady(node);
			}
		}

		// The DaemonSet's Pod on the new node: its pull is the one that no sandbox waits for.
		const holderSelector = `app.kubernetes.io/component=agent-image,app.kubernetes.io/instance=${RELEASE}`;
		let holder = "";
		await waitFor(
			"the agent-image Pod on the new node becoming ready",
			async () => {
				const pods = asArray(
					asRecord(
						await json([
							"-n",
							NAMESPACE,
							"get",
							"pods",
							"-l",
							holderSelector,
							"--field-selector",
							`spec.nodeName=${node}`,
						]),
						"Pods",
					).items,
					"Pods.items",
				);
				const ready = pods.find((pod) =>
					asArray(at(pod, ["status", "conditions"], "Pod") ?? [], "conditions").some(
						(condition) =>
							asRecord(condition, "condition").type === "Ready" &&
							asRecord(condition, "condition").status === "True",
					),
				);
				holder =
					ready === undefined ? "" : asString(at(ready, ["metadata", "name"], "Pod"), "Pod name");
				return holder !== "";
			},
			300_000,
		);
		const holderEvents = imageEvents(await json(["-n", NAMESPACE, "get", "events"]), holder);
		const pulled =
			holderEvents.find((event) => event.reason === "Pulled")?.message ?? "no Pulled event";

		// A sandbox from a copy of the review template that waits, on the new node.
		const source = await json([
			"-n",
			SANDBOXES,
			"get",
			"sandboxtemplate",
			`${RELEASE}-sandbox-review`,
		]);
		const template = gateTemplate(source, pool);
		const pod = asRecord(
			asRecord(asRecord(template.spec, "spec").podTemplate, "podTemplate").spec,
			"pod spec",
		);
		pod.nodeSelector = {
			...asRecord(pod.nodeSelector ?? {}, "nodeSelector"),
			"kubernetes.io/hostname": node,
		};
		await kubectl(["apply", "-f", "-"], JSON.stringify(template));
		await kubectl(
			["apply", "-f", "-"],
			JSON.stringify({
				apiVersion: "extensions.agents.x-k8s.io/v1beta1",
				kind: "SandboxWarmPool",
				metadata: { name: pool, namespace: SANDBOXES },
				spec: { replicas: 0, sandboxTemplateRef: { name: pool } },
			}),
		);
		const started = Date.now();
		await kubectl(
			["apply", "-f", "-"],
			JSON.stringify({
				apiVersion: "extensions.agents.x-k8s.io/v1beta1",
				kind: "SandboxClaim",
				metadata: { name: claim, namespace: SANDBOXES },
				spec: { warmPoolRef: { name: pool } },
			}),
		);
		await waitFor(
			"the sandbox becoming ready",
			async () =>
				asArray(
					at(
						await json(["-n", SANDBOXES, "get", "sandboxclaim", claim]),
						["status", "conditions"],
						"claim",
					) ?? [],
					"conditions",
				).some(
					(condition) =>
						asRecord(condition, "condition").type === "Ready" &&
						asRecord(condition, "condition").status === "True",
				),
			120_000,
		);
		const readyMs = Date.now() - started;
		// A cold claim's Sandbox and Pod take the claim's name.
		const sandboxPod = await json(["-n", SANDBOXES, "get", "pod", claim]);
		assert.equal(at(sandboxPod, ["spec", "nodeName"], "sandbox Pod"), node);
		const sandbox = claim;
		const sandboxEvents = imageEvents(await json(["-n", SANDBOXES, "get", "events"]), sandbox);
		assert.ok(
			foundPresent(sandboxEvents),
			`the sandbox on a new node pulled the agent image: ${JSON.stringify(sandboxEvents)}`,
		);
		const summary = `Pre-pull drill on a new node: the agent-image DaemonSet pulled the image (${pulled}); a sandbox claim was ready ${readyMs} ms later than its creation, and the kubelet found the image present.\n`;
		process.stdout.write(summary);
		if (process.env.GITHUB_STEP_SUMMARY !== undefined) {
			await appendFile(process.env.GITHUB_STEP_SUMMARY, summary);
		}
	} catch (error) {
		failure = new Error("pre-pull drill failed", { cause: error });
	} finally {
		for (const cleanup of [
			async () =>
				kubectl([
					"-n",
					SANDBOXES,
					"delete",
					"sandboxclaim",
					claim,
					"--ignore-not-found",
					"--wait=true",
					"--timeout=60s",
				]),
			async () =>
				kubectl([
					"-n",
					SANDBOXES,
					"delete",
					"sandboxwarmpool,sandboxtemplate",
					pool,
					"--ignore-not-found",
				]),
			async () => run("k3d", ["node", "delete", node]),
			async () => kubectl(["delete", "node", node, "--ignore-not-found", "--timeout=30s"]),
		]) {
			try {
				await cleanup();
			} catch (error) {
				failure ??= new Error("pre-pull drill cleanup failed", { cause: error });
			}
		}
	}
	if (failure !== undefined) {
		throw failure;
	}
}

if (import.meta.main) {
	await main();
}
