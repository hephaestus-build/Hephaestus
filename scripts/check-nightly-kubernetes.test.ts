import assert from "node:assert/strict";
import { test } from "node:test";

import { nightlyRefusal } from "./check-nightly-kubernetes.ts";

const repository = "hephaestus-build/Hephaestus";
const now = new Date("2026-11-10T12:00:00Z");

function api(runs: Record<string, unknown>[], jobs: Record<number, Record<string, unknown>[]>) {
	return async (path: string) => {
		if (path.includes("/workflows/kubernetes-nightly.yml/runs?")) {
			return { workflow_runs: runs };
		}
		const id = Number(/\/runs\/(?<id>\d+)\/jobs/u.exec(path)?.groups?.id);
		return [{ jobs: jobs[id] ?? [] }];
	};
}

function run(id: number, conclusion: string, createdAt = "2026-11-10T02:23:00Z") {
	return {
		id,
		conclusion,
		created_at: createdAt,
		html_url: `https://example.test/runs/${String(id)}`,
	};
}

const passingLegs = [
	{ name: "Newest main build", conclusion: "success" },
	{ name: "Kubernetes / Chart", conclusion: "success" },
	{ name: "Kubernetes (k3d 1.37 amd64) / k3s 1.37 amd64", conclusion: "success" },
	{ name: "Kubernetes (k3d 1.35 arm64) / k3s 1.35 arm64", conclusion: "success" },
	{ name: "Kubernetes / kind 1.35 Calico", conclusion: "skipped" },
];

void test("a nightly run whose cluster legs passed qualifies a release, even when the footprint benchmark failed", async () => {
	const result = await nightlyRefusal(
		repository,
		api([run(2, "failure")], {
			2: [...passingLegs, { name: "Sandbox footprint", conclusion: "failure" }],
		}),
		now,
	);
	assert.equal(result.refusal, undefined);
	assert.equal(result.runUrl, "https://example.test/runs/2");
});

void test("a failed cluster leg refuses the release and names the leg", async () => {
	const result = await nightlyRefusal(
		repository,
		api([run(3, "failure")], {
			3: [
				...passingLegs,
				{ name: "Kubernetes (calico 1.35 amd64) / kind 1.35 Calico", conclusion: "failure" },
			],
		}),
		now,
	);
	assert.match(
		result.refusal ?? "",
		/failed: Kubernetes \(calico 1\.35 amd64\) \/ kind 1\.35 Calico\./u,
	);
});

void test("a cancelled newest run is passed over for the newest run that ran", async () => {
	const result = await nightlyRefusal(
		repository,
		api([run(5, "cancelled"), run(4, "failure")], {
			4: [{ name: "Kubernetes (k3d 1.37 amd64) / k3s 1.37 amd64", conclusion: "failure" }],
			5: passingLegs,
		}),
		now,
	);
	assert.match(result.refusal ?? "", /k3s 1\.37 amd64/u);
});

void test("a failed nightly drill leg refuses release admission", async () => {
	const name = "Kubernetes / k3s 1.37 amd64 drills";
	const result = await nightlyRefusal(
		repository,
		api([run(8, "failure")], {
			8: [...passingLegs, { name, conclusion: "failure" }],
		}),
		now,
	);
	assert.equal(result.refusal?.includes(name), true);
});

void test("a stale run, a run with no cluster leg and no run at all refuse the release", async () => {
	const stale = await nightlyRefusal(
		repository,
		api([run(6, "success", "2026-11-07T02:23:00Z")], { 6: passingLegs }),
		now,
	);
	assert.match(stale.refusal ?? "", /more than 48 hours ago/u);
	const empty = await nightlyRefusal(
		repository,
		api([run(7, "failure")], { 7: [{ name: "Newest main build", conclusion: "success" }] }),
		now,
	);
	assert.match(empty.refusal ?? "", /qualified no cluster leg/u);
	const none = await nightlyRefusal(repository, api([], {}), now);
	assert.match(none.refusal ?? "", /No nightly Kubernetes run/u);
});

void test("the static cluster names retain nightly admission and failure visibility", async () => {
	const jobs = passingLegs.map((job) => ({
		...job,
		name: job.name.replace(/^Kubernetes \([^)]*\)/u, "Kubernetes"),
	}));
	const admitted = await nightlyRefusal(repository, api([run(2, "success")], { 2: jobs }), now);
	assert.equal(admitted.refusal, undefined);
	const failed = jobs.map((job) => ({
		...job,
		conclusion: job.name === "Kubernetes / k3s 1.37 amd64" ? "failure" : job.conclusion,
	}));
	const refused = await nightlyRefusal(repository, api([run(3, "failure")], { 3: failed }), now);
	assert.match(refused.refusal ?? "", /Kubernetes \/ k3s 1\.37 amd64/u);
});
