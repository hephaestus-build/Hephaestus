/** A disposable k3d node exercises kubelet eviction without exhausting the runner's memory. */
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { setTimeout as sleep } from "node:timers/promises";

import { NAMESPACE, RELEASE } from "./kubernetes-cluster.ts";
import { asArray, asRecord, asString, at, parseJson } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";
import { parseResolvedImages } from "./resolve-release-images.ts";

const namespace = `memory-drill-${process.pid}`;
const tiers = ["reviews", "conversations", "roles"];
/** The chart's default priority resources, needed before a concurrent install is ready. */
export const priorityClassResources = tiers.map(
	(tier) => `priorityclass/${NAMESPACE}-${RELEASE}-${tier}`,
);
export const pressureNodeTaint = "hephaestus.build/memory-drill";
const allocations = new Map([
	["reviews", 32],
	["conversations", 64],
	["roles", 96],
]);
/** Each fixture's memory request and limit, in MiB. */
const REQUEST_MI = 192;
/**
 * How long the drill waits for the kubelet's summary to show every fixture's touched memory. A new
 * container's sample can lag on a loaded runner. The wait stays inside the soft eviction grace
 * period below, which the kubelet counts from the node's start.
 */
const SAMPLE_WAIT_MS = 120_000;
const kubectl = async (args: string[], input?: string) => output("kubectl", args, { input });
const apply = async (manifest: object) => {
	await kubectl(["apply", "-f", "-"], JSON.stringify(manifest));
};

/** Read only eviction events for our fixtures. A missing or ambiguous order fails the proof. */
export function evictionOrder(events: unknown): string[] {
	const ordered = asArray(asRecord(events, "events").items, "events.items")
		.map((event) => asRecord(event, "event"))
		.filter((event) => event.reason === "Evicted")
		.map((event) => ({
			name: asString(at(event, ["involvedObject", "name"], "event"), "Pod name"),
			time: Date.parse(
				asString(
					event.eventTime ??
						event.firstTimestamp ??
						at(event, ["metadata", "creationTimestamp"], "event"),
					"eviction time",
				),
			),
		}))
		.filter((event) => tiers.includes(event.name))
		.toSorted((a, b) => a.time - b.time);
	for (const [index, event] of ordered.entries()) {
		assert.ok(Number.isFinite(event.time), "eviction time must be an ISO timestamp");
		assert.notEqual(
			event.time,
			ordered[index - 1]?.time,
			"eviction timestamps must establish an order",
		);
	}
	return ordered.map((event) => event.name);
}

/** Each fixture's working set in the kubelet's stats summary, or undefined while it has none. */
export function workingSets(stats: unknown): Map<string, number | undefined> {
	const pods = asArray(asRecord(stats, "stats").pods, "stats.pods");
	return new Map(
		tiers.map((tier) => {
			const pod = pods.find((entry) => at(entry, ["podRef", "name"], "stats Pod") === tier);
			const workingSet =
				pod === undefined ? undefined : at(pod, ["memory", "workingSetBytes"], "stats Pod");
			return [tier, typeof workingSet === "number" ? workingSet : undefined];
		}),
	);
}

/**
 * The fixtures whose sample does not yet show touched memory below the request, so in one eviction
 * group. Each line names the sample that the drill read.
 */
export function unsampled(samples: Map<string, number | undefined>): string[] {
	return tiers.flatMap((tier) => {
		const workingSet = samples.get(tier);
		const allocated = Number(allocations.get(tier));
		if (
			workingSet !== undefined &&
			workingSet > allocated * 1024 * 1024 &&
			workingSet < REQUEST_MI * 1024 * 1024
		) {
			return [];
		}
		const read =
			workingSet === undefined ? "no sample" : `${(workingSet / 1024 / 1024).toFixed(1)} MiB`;
		return [
			`${tier}: ${read}; it needs more than ${allocated} MiB and less than ${REQUEST_MI} MiB`,
		];
	});
}

export async function memoryDrill(imagesFile: string, cluster = "hephaestus"): Promise<void> {
	assert.match(cluster, /^hephaestus(?:-[a-z0-9-]+)?$/u);
	// A separate cluster name permits a private rehearsal without touching a shared qualification cluster.
	const nodeName = `${cluster}-memory-pressure-${process.pid}`;
	assert.equal(process.env.CI, "true", "the pressure drill runs only on a disposable CI cluster");
	const context = await output("kubectl", ["config", "current-context"]);
	assert.equal(context.trim(), `k3d-${cluster}`);
	const resolved = await readFile(imagesFile, "utf8");
	const image = parseResolvedImages(resolved).find((entry) => entry.image === "agent-pi");
	assert.ok(image, "the drill needs this run's agent image");
	let failure: Error | undefined;
	// A high soft threshold exercises the real eviction manager while the host still has spare memory.
	// Its grace period lets all three fixtures start before eviction; production soft eviction stays off.
	try {
		// The roll job may still be installing. A multi-name wait aggregates NotFound errors,
		// which kubectl cannot treat as a resource that is still being created.
		const prioritiesDeadline = Date.now() + 300_000;
		for (const resource of priorityClassResources) {
			await kubectl([
				"wait",
				"--for=create",
				resource,
				`--timeout=${Math.max(0, prioritiesDeadline - Date.now())}ms`,
			]);
		}
		await run("k3d", [
			"node",
			"create",
			nodeName,
			"--cluster",
			cluster,
			"--role",
			"agent",
			"--k3s-node-label",
			`${pressureNodeTaint}=true`,
			"--k3s-arg",
			`--node-taint=${pressureNodeTaint}=true:NoSchedule`,
			"--k3s-arg",
			"--kubelet-arg=eviction-soft=memory.available<99.99%",
			"--k3s-arg",
			"--kubelet-arg=eviction-soft-grace-period=memory.available=300s",
			"--k3s-arg",
			"--kubelet-arg=eviction-max-pod-grace-period=1",
			"--wait",
			"--timeout",
			"60s",
		]);
		const nodes = asArray(
			asRecord(
				parseJson(await kubectl(["get", "nodes", "-l", `${pressureNodeTaint}=true`, "-o", "json"])),
				"nodes",
			).items,
			"nodes.items",
		);
		assert.equal(nodes.length, 1);
		const node = asString(at(nodes[0], ["metadata", "name"], "node"), "node name");
		await apply({
			apiVersion: "v1",
			kind: "Namespace",
			metadata: { name: namespace, labels: { "pod-security.kubernetes.io/enforce": "restricted" } },
		});
		const priorities = asArray(
			asRecord(
				parseJson(
					await kubectl([
						"get",
						"priorityclasses",
						"-l",
						`app.kubernetes.io/instance=${RELEASE}`,
						"-o",
						"json",
					]),
				),
				"priorities",
			).items,
			"priorities.items",
		);
		// Both creation order and reclaimable memory oppose the expected eviction order.
		for (const tier of tiers.toReversed()) {
			const priority = priorities.find((entry) =>
				asString(at(entry, ["metadata", "name"], "priority"), "priority name").endsWith(`-${tier}`),
			);
			if (priority === undefined) {
				throw new Error(`the installed chart must provide the ${tier} priority`);
			}
			await apply({
				apiVersion: "v1",
				kind: "Pod",
				metadata: { name: tier, namespace },
				spec: {
					nodeName: node,
					priorityClassName: at(priority, ["metadata", "name"], "priority"),
					automountServiceAccountToken: false,
					restartPolicy: "Never",
					terminationGracePeriodSeconds: 1,
					tolerations: [{ key: pressureNodeTaint, operator: "Exists", effect: "NoSchedule" }],
					securityContext: {
						runAsNonRoot: true,
						runAsUser: 1000,
						seccompProfile: { type: "RuntimeDefault" },
					},
					containers: [
						{
							name: "memory",
							image: `${image.repository}@${image.indexDigest}`,
							command: [
								"node",
								"-e",
								`globalThis.pages=Buffer.alloc(${Number(allocations.get(tier))}*1024*1024,1); setInterval(()=>{},1000)`,
							],
							resources: {
								requests: { cpu: "10m", memory: `${REQUEST_MI}Mi` },
								limits: { memory: `${REQUEST_MI}Mi` },
							},
							securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } },
						},
					],
				},
			});
		}
		await kubectl([
			"-n",
			namespace,
			"wait",
			"pod",
			"--all",
			"--for=condition=Ready",
			"--timeout=240s",
		]);
		const deadline = Date.now() + SAMPLE_WAIT_MS;
		let missing: string[] = [];
		do {
			const stats = parseJson(
				await kubectl(["get", "--raw", `/api/v1/nodes/${node}/proxy/stats/summary`]),
			);
			missing = unsampled(workingSets(stats));
			if (missing.length > 0) {
				await sleep(2000);
			}
		} while (missing.length > 0 && Date.now() < deadline);
		assert.deepEqual(
			missing,
			[],
			"each fixture must consume touched memory below its request, in the same eviction group",
		);
		for (const tier of tiers) {
			await kubectl([
				"-n",
				namespace,
				"wait",
				`pod/${tier}`,
				"--for=jsonpath={.status.reason}=Evicted",
				"--timeout=6m",
			]);
		}
		const events = parseJson(await kubectl(["-n", namespace, "get", "events", "-o", "json"]));
		assert.deepEqual(
			evictionOrder(events),
			tiers,
			"kubelet must evict reviews, conversations, then roles",
		);
		const pods = asArray(
			asRecord(parseJson(await kubectl(["-n", namespace, "get", "pods", "-o", "json"])), "Pods")
				.items,
			"Pods.items",
		);
		for (const pod of pods) {
			assert.equal(at(pod, ["status", "phase"], "Pod"), "Failed");
			assert.match(asString(at(pod, ["status", "message"], "Pod"), "eviction message"), /memory/iu);
			const statuses = at(pod, ["status", "containerStatuses"], "Pod");
			for (const status of asArray(statuses, "container statuses")) {
				const { terminated } = asRecord(
					asRecord(status, "container status").state,
					"container state",
				);
				if (terminated !== undefined) {
					assert.notEqual(asRecord(terminated, "termination").reason, "OOMKilled");
				}
			}
		}
		process.stdout.write(
			"memory drill: kubelet evicted reviews before conversations before roles; no kernel OOM\n",
		);
	} catch (error) {
		failure = new Error("memory drill failed", { cause: error });
	} finally {
		for (const cleanup of [
			async () =>
				kubectl([
					"delete",
					"namespace",
					namespace,
					"--ignore-not-found",
					"--wait=true",
					"--timeout=30s",
				]),
			async () => run("k3d", ["node", "delete", `k3d-${nodeName}-0`]),
			async () =>
				kubectl(["delete", "node", `k3d-${nodeName}-0`, "--ignore-not-found", "--timeout=30s"]),
		]) {
			try {
				await cleanup();
			} catch (error) {
				process.stderr.write("memory drill: a cleanup step failed\n");
				failure ??= new Error("memory drill cleanup failed", { cause: error });
			}
		}
	}
	if (failure !== undefined) {
		throw failure;
	}
}

if (import.meta.main) {
	await memoryDrill(process.argv[2] ?? "release-images.tsv", process.argv[3]);
}
