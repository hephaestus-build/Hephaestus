import assert from "node:assert/strict";
import { test } from "node:test";

import {
	deliveryMetrics,
	duration,
	isPatch,
	isRevert,
	parseDeployment,
	renderDeliveryMetrics,
	type Change,
	type Deployment,
} from "./report-delivery-metrics.ts";

const MINUTE = 60_000;
const window = { since: 0, until: 7 * 24 * 60 * MINUTE };
// Main's first-parent history, oldest first: a, b, c, d.
const position = new Map([
	["a", 1],
	["b", 2],
	["c", 3],
	["d", 4],
]);

function change(overrides: Partial<Change> = {}): Change {
	return {
		number: 1,
		title: "fix(server): keep feedback current",
		firstCommitAt: 0,
		openedAt: 10 * MINUTE,
		mergedAt: 60 * MINUTE,
		mergeCommit: "b",
		versionPr: false,
		...overrides,
	};
}

function deployment(overrides: Partial<Deployment> = {}): Deployment {
	return {
		environment: "Staging",
		sha: "b",
		createdAt: 70 * MINUTE,
		outcome: "success",
		finishedAt: 75 * MINUTE,
		rollback: false,
		...overrides,
	};
}

await test("a change arrives with the first later build of its commit or a descendant", () => {
	const metrics = deliveryMetrics(
		window,
		[change()],
		[
			// An older commit, even deployed later, does not carry the change.
			deployment({ sha: "a", createdAt: 65 * MINUTE, finishedAt: 66 * MINUTE }),
			deployment({ sha: "c", createdAt: 80 * MINUTE, finishedAt: 90 * MINUTE }),
			deployment({ sha: "b", createdAt: 70 * MINUTE, outcome: "failure", finishedAt: 72 * MINUTE }),
			deployment({
				environment: "Production",
				sha: "d",
				createdAt: 200 * MINUTE,
				finishedAt: 205 * MINUTE,
			}),
		],
		[{ tag: "v1.0.0", commit: "d", publishedAt: 150 * MINUTE }],
		position,
	);
	assert.equal(metrics.leadTime.commitToMerge.p50, 60 * MINUTE);
	assert.equal(metrics.leadTime.mergeToStaging.p50, 30 * MINUTE);
	assert.equal(metrics.leadTime.mergeToRelease.p50, 90 * MINUTE);
	assert.equal(metrics.leadTime.mergeToProduction.p50, 145 * MINUTE);
	assert.equal(metrics.leadTime.openToProduction.p50, 195 * MINUTE);
	assert.deepEqual(
		{ ...metrics.changes, revertRate: undefined },
		{
			merged: 1,
			reverts: 0,
			revertRate: undefined,
			unplaced: 0,
			awaitingStaging: 0,
			awaitingRelease: 0,
			awaitingProduction: 0,
		},
	);
});

await test("the Version PR is not a change, and an undeployed change is reported as waiting", () => {
	const metrics = deliveryMetrics(
		window,
		[
			change({ number: 2, versionPr: true }),
			change({ mergeCommit: "d" }),
			change({ mergeCommit: "x" }),
		],
		[deployment()],
		[],
		position,
	);
	assert.equal(metrics.changes.merged, 2);
	assert.equal(metrics.changes.unplaced, 1);
	assert.equal(metrics.changes.awaitingStaging, 1);
	assert.equal(metrics.leadTime.mergeToStaging.count, 0);
});

await test("a failed or rolled-back deployment counts against the change failure rate until restored", () => {
	const [staging] = deliveryMetrics(
		window,
		[],
		[
			deployment({ createdAt: 10 * MINUTE, finishedAt: 15 * MINUTE }),
			deployment({ createdAt: 20 * MINUTE, outcome: "failure", finishedAt: 35 * MINUTE }),
			deployment({ createdAt: 40 * MINUTE, finishedAt: 50 * MINUTE }),
			// Moving back marks the deployment it replaced as failed.
			deployment({ createdAt: 60 * MINUTE, finishedAt: 65 * MINUTE, rollback: true }),
			deployment({ createdAt: 70 * MINUTE, outcome: undefined, finishedAt: undefined }),
		],
		[],
		position,
	).environments;
	assert.ok(staging);
	assert.equal(staging.deployments, 4);
	assert.equal(staging.successful, 2);
	assert.equal(staging.changeFailureRate, 0.5);
	assert.deepEqual(
		{ count: staging.timeToRestore.count, unrestored: staging.unrestored },
		{ count: 2, unrestored: 0 },
	);
	// The failure at 35 is restored only at 65, because the success in between was rolled back.
	// The rolled-back deployment, done at 50, is restored at 65.
	assert.equal(staging.timeToRestore.p50, 22.5 * MINUTE);
});

await test("a deployment's outcome skips the inactive status that replaced it", () => {
	const parsed = parseDeployment(
		"Staging",
		{ sha: "b", created_at: "2026-10-24T12:00:00Z", payload: { allow_rollback: true } },
		[
			{ state: "inactive", created_at: "2026-10-24T13:00:00Z" },
			{ state: "success", created_at: "2026-10-24T12:04:00Z" },
			{ state: "in_progress", created_at: "2026-10-24T12:00:00Z" },
		],
	);
	assert.equal(parsed.outcome, "success");
	assert.equal(parsed.finishedAt, Date.parse("2026-10-24T12:04:00Z"));
	assert.equal(parsed.rollback, true);
	assert.equal(
		parseDeployment("Production", { sha: "b", created_at: "2026-10-24T12:00:00Z" }, []).outcome,
		undefined,
	);
});

await test("a repeated deployment of one build and a patch release are counted", () => {
	const metrics = deliveryMetrics(
		window,
		[],
		[
			deployment({ createdAt: 10 * MINUTE }),
			deployment({ createdAt: 20 * MINUTE }),
			deployment({ sha: "c", createdAt: 30 * MINUTE }),
		],
		[
			{ tag: "v1.2.0", commit: "b", publishedAt: 10 * MINUTE },
			{ tag: "v1.2.1", commit: "c", publishedAt: 20 * MINUTE },
		],
		position,
	);
	assert.equal(metrics.environments[0]?.repeats, 1);
	assert.deepEqual(metrics.releases, { published: 2, patches: 1, patchShare: 0.5 });
	assert.ok(isPatch("v0.94.10"));
	assert.ok(!isPatch("v0.95.0"));
});

await test("reverts are recognized from either revert title", () => {
	assert.ok(isRevert('Revert "fix(server): keep feedback current"'));
	assert.ok(isRevert("revert(release): defer v0.75.0"));
	assert.ok(!isRevert("fix(server): revert the cache to its old key"));
});

await test("the report shows missing measurements as missing, not as zero", () => {
	const rendered = renderDeliveryMetrics(
		"Delivery metrics",
		deliveryMetrics(window, [], [], [], position),
	);
	assert.match(rendered, /\| Merged → running in production \| — \| — \| 0 \|/u);
	assert.match(rendered, /\| Production \| 0 \| 0\.0 \| 0 \| — \(0\/0\)/u);
	assert.match(rendered, /Patch releases \(rework\): 0 of 0 \(—\)/u);
	assert.equal(duration(90 * MINUTE), "90 min");
	assert.equal(duration(3 * 60 * MINUTE), "3.0 h");
	assert.equal(duration(72 * 60 * MINUTE), "3.0 d");
});
