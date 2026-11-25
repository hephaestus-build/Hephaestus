/**
 * Proves on the CI cluster that release history records each rollout of the server Deployment once, by the revision
 * that the Deployment controller gave it (docs/admin/instance-admin.mdx § Update checks). It runs after
 * `kubernetes-cluster.ts install` and `upgrade`. A server Pod that restarts adds no entry. `helm rollback` to the
 * install, and then `kubectl rollout undo`, each scale an earlier ReplicaSet up again under a new revision, and each
 * adds that revision as the newest entry.
 *
 *   CI=true node scripts/release-history-drill.ts   (only with the k3d context of the disposable CI cluster)
 *
 * The Helm leg runs before the kubectl leg. `kubectl rollout undo` writes the Deployment's template with its own field
 * manager, so a later Helm change to the same fields conflicts. The drill is the last step of its leg for that reason.
 */
import assert from "node:assert/strict";
import { appendFile } from "node:fs/promises";
import { setTimeout as sleep } from "node:timers/promises";

import { DEV_CLUSTER, NAMESPACE, psql, RELEASE } from "./kubernetes-cluster.ts";
import { asArray, asRecord, at, parseJson } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";

const DEPLOYMENT = `${RELEASE}-server`;
const SERVERS = `app.kubernetes.io/instance=${RELEASE},app.kubernetes.io/component=server`;
const REVISION = "deployment.kubernetes.io/revision";
const REVISION_HISTORY = "deployment.kubernetes.io/revision-history";

const kubectl = async (...args: string[]) => output("kubectl", ["-n", NAMESPACE, ...args]);
const json = async (...args: string[]) => parseJson(await kubectl(...args, "-o", "json"));

/** One entry of the history: a revision and the release that it ran. */
interface Entry {
	revision: number;
	version: string;
}

const describe = (entries: readonly Entry[]) =>
	entries.map((entry) => `${entry.revision}:${entry.version}`).join(" ");

/** The recorded entries of the server Deployment, newest first. */
async function history(uid: string): Promise<Entry[]> {
	const rows = await psql(
		`SELECT revision || ' ' || version FROM release_activation WHERE deployment_uid = '${uid}'` +
			" ORDER BY revision DESC",
	);
	return rows
		.split("\n")
		.filter((row) => row !== "")
		.map((row) => {
			const [revision = "", version = ""] = row.split(" ");
			return { revision: Number(revision), version };
		});
}

/** Every revision that the Deployment's ReplicaSets carry, the earlier ones of a reused ReplicaSet included. */
async function clusterRevisions(): Promise<number[]> {
	const replicaSets = asArray(
		asRecord(await json("get", "replicasets", "-l", SERVERS), "replicasets").items,
		"items",
	);
	return replicaSets
		.flatMap((replicaSet) => {
			const annotations = asRecord(
				at(replicaSet, ["metadata", "annotations"], "replicaset") ?? {},
				"annotations",
			);
			const earlier =
				typeof annotations[REVISION_HISTORY] === "string" ? annotations[REVISION_HISTORY] : "";
			return [
				typeof annotations[REVISION] === "string" ? annotations[REVISION] : "",
				...earlier.split(","),
			];
		})
		.filter((revision) => revision !== "")
		.map(Number)
		.toSorted((left, right) => right - left);
}

/** The revision and the version that the finished rollout runs. */
async function running(): Promise<Entry> {
	await kubectl("rollout", "status", `deployment/${DEPLOYMENT}`, "--timeout=10m");
	const deployment = await json("get", "deployment", DEPLOYMENT);
	const annotations = asRecord(
		at(deployment, ["metadata", "annotations"], "deployment"),
		"annotations",
	);
	const containers = asArray(
		at(deployment, ["spec", "template", "spec", "containers"], "deployment"),
		"containers",
	);
	const variables = asArray(at(containers[0], ["env"], "container"), "env");
	const version = variables.find((variable) => at(variable, ["name"], "env") === "APP_VERSION");
	return {
		revision: Number(annotations[REVISION]),
		version: String(at(version, ["value"], "APP_VERSION")),
	};
}

/** Reads the history until `done` holds, at most three minutes: a server records its rollout when it is ready. */
async function poll(
	step: string,
	uid: string,
	done: (entries: Entry[]) => boolean,
): Promise<Entry[]> {
	const deadline = Date.now() + 3 * 60_000;
	for (;;) {
		const entries = await history(uid);
		if (done(entries)) {
			return entries;
		}
		if (Date.now() > deadline) {
			throw new Error(`${step}: history ${describe(entries)}`);
		}
		await sleep(5000);
	}
}

/** A server Pod: its name, its UID, its ReplicaSet's template hash and whether it is ready. */
interface ServerPod {
	name: string;
	uid: string;
	replicaSet: string;
	ready: boolean;
}

/** The server Pods that are not being deleted. */
async function serverPods(): Promise<ServerPod[]> {
	const pods = asArray(asRecord(await json("get", "pods", "-l", SERVERS), "pods").items, "items");
	return pods
		.filter((pod) => at(pod, ["metadata", "deletionTimestamp"], "pod") === undefined)
		.map((pod) => ({
			name: String(at(pod, ["metadata", "name"], "pod")),
			uid: String(at(pod, ["metadata", "uid"], "pod")),
			replicaSet: String(at(pod, ["metadata", "labels", "pod-template-hash"], "pod")),
			ready: asArray(at(pod, ["status", "conditions"], "pod") ?? [], "conditions").some(
				(condition) =>
					at(condition, ["type"], "condition") === "Ready" &&
					at(condition, ["status"], "condition") === "True",
			),
		}));
}

/** Waits until a Pod that was not among `earlier` runs in the ReplicaSet `replicaSet` and is ready. */
async function waitForReplacement(earlier: ReadonlySet<string>, replicaSet: string): Promise<void> {
	const deadline = Date.now() + 10 * 60_000;
	for (;;) {
		const pods = await serverPods();
		if (pods.some((pod) => !earlier.has(pod.uid) && pod.replicaSet === replicaSet && pod.ready)) {
			return;
		}
		if (Date.now() > deadline) {
			throw new Error(`no replacement server Pod in ReplicaSet ${replicaSet} became ready`);
		}
		await sleep(5000);
	}
}

/** Waits until the history is `expected`, then requires that one more round of reports adds nothing. */
async function expectHistory(step: string, uid: string, expected: readonly Entry[]): Promise<void> {
	await poll(step, uid, (entries) => describe(entries) === describe(expected));
	// Every server reads its revision each minute; one more round must add nothing.
	await sleep(75_000);
	assert.equal(describe(await history(uid)), describe(expected), step);
	console.log(`${step}: ${describe(expected)}`);
	const summary = process.env.GITHUB_STEP_SUMMARY;
	if (summary !== undefined && summary !== "") {
		await appendFile(summary, `- ${step}: \`${describe(expected)}\`, newest first\n`);
	}
}

if (import.meta.main) {
	// It deletes a server Pod and rolls the release back twice, so only the disposable CI cluster runs it.
	const context =
		process.env.CI === "true" ? await output("kubectl", ["config", "current-context"]) : "";
	if (context.trim() !== `k3d-${DEV_CLUSTER}`) {
		throw new Error("the destructive release history drill requires the disposable CI cluster");
	}
	const uid = String(
		at(await json("get", "deployment", DEPLOYMENT), ["metadata", "uid"], "deployment"),
	);
	const upgraded = await running();
	// The baseline claims nothing that Kubernetes did not roll out. An earlier step of the leg can restore the database
	// to a point in time, which removes the rows of later revisions, so every recorded revision must be one of the
	// cluster's, and the newest must be the one that runs. Each step below then adds exactly one revision.
	const revisions = await clusterRevisions();
	assert.ok(
		revisions.length >= 2,
		`install and upgrade are two revisions, not ${revisions.join(", ")}`,
	);
	assert.equal(revisions[0], upgraded.revision, "the upgrade is the newest revision");
	const recorded = await poll(
		`The running revision ${upgraded.revision}`,
		uid,
		(entries) => entries[0]?.revision === upgraded.revision,
	);
	for (const entry of recorded) {
		assert.ok(
			revisions.includes(entry.revision),
			`revision ${entry.revision} is one that Kubernetes rolled out`,
		);
	}
	assert.deepEqual(
		recorded.map((entry) => entry.revision),
		[...new Set(recorded.map((entry) => entry.revision))].toSorted((left, right) => right - left),
		"each revision once, newest first",
	);
	assert.equal(recorded[0]?.version, upgraded.version, "the newest entry runs the upgrade");
	await expectHistory("After install and upgrade", uid, recorded);

	const before = await serverPods();
	const [pod] = before;
	assert.ok(pod !== undefined, "a server Pod runs");
	await kubectl("delete", `pod/${pod.name}`, "--wait=true");
	// A deleted Pod leaves the Deployment's generation as it was, so its rollout status can pass on the old counts.
	// The check waits for the replacement: a new Pod of the same ReplicaSet that is ready, and so has reported.
	await waitForReplacement(new Set(before.map((each) => each.uid)), pod.replicaSet);
	const restarted = await running();
	assert.equal(restarted.revision, upgraded.revision, "a restart is no rollout");
	await expectHistory("A server Pod restarted", uid, recorded);

	await run("helm", [
		"rollback",
		RELEASE,
		"1",
		"--namespace",
		NAMESPACE,
		"--wait=watcher",
		"--wait-for-jobs",
		"--timeout",
		"20m",
	]);
	const rolledBack = await running();
	assert.equal(rolledBack.revision, upgraded.revision + 1, "`helm rollback` is the next revision");
	const afterHelm = [rolledBack, ...recorded];
	await expectHistory("helm rollback to the install", uid, afterHelm);

	await kubectl("rollout", "undo", `deployment/${DEPLOYMENT}`);
	const undone = await running();
	assert.equal(
		undone.revision,
		rolledBack.revision + 1,
		"`kubectl rollout undo` is the next revision",
	);
	assert.equal(undone.version, upgraded.version, "`kubectl rollout undo` returns to the upgrade");
	await expectHistory("kubectl rollout undo", uid, [undone, ...afterHelm]);
}
