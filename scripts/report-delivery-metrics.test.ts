import assert from "node:assert/strict";
import { test } from "node:test";

import { output } from "./lib/process.ts";
import {
	classify,
	deliveryMetrics,
	duration,
	isRevert,
	mergedDateRanges,
	parseChanges,
	parseDeployment,
	parseReleases,
	renderDeliveryMetrics,
	retryTransient,
	searchCount,
	versionBranch,
	type Change,
	type Deployment,
} from "./report-delivery-metrics.ts";

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;
const window = { since: 0, until: 7 * DAY };
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
		pushedAt: 20 * MINUTE,
		greenAt: 30 * MINUTE,
		queuedAt: 35 * MINUTE,
		queueEntries: 1,
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
			deployment({ sha: "b", createdAt: 70 * MINUTE, outcome: "failure", finishedAt: 72 * MINUTE }),
			deployment({ sha: "c", createdAt: 80 * MINUTE, finishedAt: 90 * MINUTE }),
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
	assert.equal(metrics.leadTime.pushToGreen.p50, 10 * MINUTE);
	assert.equal(metrics.leadTime.greenToQueued.p50, 5 * MINUTE);
	assert.equal(metrics.leadTime.queuedToMerged.p50, 25 * MINUTE);
	assert.equal(metrics.leadTime.commitToMerge.p50, 60 * MINUTE);
	assert.equal(metrics.leadTime.mergeToStaging.p50, 30 * MINUTE);
	assert.equal(metrics.leadTime.mergeToRelease.p50, 90 * MINUTE);
	assert.equal(metrics.leadTime.mergeToProduction.p50, 145 * MINUTE);
	assert.equal(metrics.leadTime.openToProduction.p50, 195 * MINUTE);
	assert.equal(metrics.changes.revertRate, 0);
	assert.equal(metrics.changes.awaitingProduction, 0);
});

await test("a rolled-back deployment does not end the lead time of the changes it carried", () => {
	const metrics = deliveryMetrics(
		window,
		[change({ mergeCommit: "c" })],
		[
			deployment({ sha: "c", createdAt: 70 * MINUTE, finishedAt: 75 * MINUTE }),
			deployment({ sha: "b", createdAt: 80 * MINUTE, finishedAt: 85 * MINUTE }),
			deployment({ sha: "d", createdAt: 100 * MINUTE, finishedAt: 120 * MINUTE }),
		],
		[],
		position,
	);
	assert.equal(metrics.leadTime.mergeToStaging.p50, 60 * MINUTE);
});

await test("a rollback is a move back on main's history, and a repeat of what runs is no deployment", () => {
	const deployments = [
		deployment({ sha: "b", createdAt: 1 }),
		// Promoting what runs again, with or without a freeze, moves nothing.
		deployment({ sha: "b", createdAt: 2 }),
		deployment({ sha: "c", createdAt: 3 }),
		deployment({ sha: "b", createdAt: 4 }),
		deployment({ sha: "d", createdAt: 5, outcome: undefined, finishedAt: undefined }),
	];
	assert.deepEqual(
		[...classify(deployments, position).entries()].map(([{ createdAt }, verdict]) => [
			createdAt,
			verdict,
		]),
		[
			[1, "success"],
			[2, "repeat"],
			[3, "failed"],
			[4, "success"],
			[5, "undecided"],
		],
	);
	const [staging] = deliveryMetrics(window, [], deployments, [], position).environments;
	assert.ok(staging);
	assert.deepEqual(
		{
			deployments: staging.deployments,
			successful: staging.successful,
			repeats: staging.repeats,
			rate: staging.changeFailRate,
		},
		{ deployments: 3, successful: 2, repeats: 1, rate: 1 / 3 },
	);
});

await test("consecutive failures are one incident, recovered by the next deployment that stays", () => {
	const [staging] = deliveryMetrics(
		window,
		[],
		[
			deployment({ sha: "a", createdAt: 10 * MINUTE, finishedAt: 15 * MINUTE }),
			deployment({ sha: "b", createdAt: 20 * MINUTE, outcome: "failure", finishedAt: 30 * MINUTE }),
			deployment({ sha: "c", createdAt: 40 * MINUTE, outcome: "error", finishedAt: 45 * MINUTE }),
			deployment({ sha: "d", createdAt: 50 * MINUTE, finishedAt: 60 * MINUTE }),
			deployment({ sha: "a", createdAt: 70 * MINUTE, outcome: "failure", finishedAt: 80 * MINUTE }),
		],
		[],
		position,
	).environments;
	assert.ok(staging);
	assert.deepEqual(
		{ count: staging.recoveryTime.count, p50: staging.recoveryTime.p50 },
		{ count: 1, p50: 30 * MINUTE },
	);
	assert.equal(staging.unrecovered, 1);
});

await test("each window counts only what happened inside it", () => {
	const changes = [change({ mergedAt: 2 * DAY }), change({ number: 2, mergedAt: 20 * DAY })];
	const deployments = [
		deployment({ sha: "a", createdAt: 3 * DAY, finishedAt: 3 * DAY }),
		deployment({ sha: "b", createdAt: 21 * DAY, finishedAt: 21 * DAY }),
	];
	const week = deliveryMetrics(
		{ since: 14 * DAY, until: 21 * DAY + 1 },
		changes,
		deployments,
		[],
		position,
	);
	const month = deliveryMetrics(
		{ since: 0, until: 21 * DAY + 1 },
		changes,
		deployments,
		[],
		position,
	);
	assert.equal(week.changes.merged, 1);
	assert.equal(month.changes.merged, 2);
	assert.equal(week.environments[0]?.successful, 1);
	assert.equal(month.environments[0]?.successful, 2);
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
	assert.equal(metrics.changes.requeued, 0);
	assert.equal(metrics.changes.unplaced, 1);
	assert.equal(metrics.changes.awaitingStaging, 1);
	assert.equal(metrics.leadTime.mergeToStaging.count, 0);
});

await test("GitHub's records parse at the trust boundary", () => {
	const pages = [
		{
			data: {
				search: {
					issueCount: 2,
					nodes: [
						{
							number: 5,
							title: "fix: a",
							createdAt: "2026-10-24T10:00:00Z",
							mergedAt: "2026-10-24T11:00:00Z",
							headRefName: "changeset-release/main",
							mergeCommit: { oid: "b" },
							commits: { nodes: [{ commit: { authoredDate: "2026-10-24T09:00:00Z" } }] },
							head: {
								nodes: [
									{
										commit: {
											status: {
												contexts: [
													{
														context: "All CI Passed",
														state: "FAILURE",
														createdAt: "2026-10-24T10:20:00Z",
													},
													{
														context: "All CI Passed",
														state: "SUCCESS",
														createdAt: "2026-10-24T10:40:00Z",
													},
													{
														context: "CodeRabbit",
														state: "SUCCESS",
														createdAt: "2026-10-24T10:50:00Z",
													},
												],
											},
											checkSuites: {
												pageInfo: { hasNextPage: false },
												nodes: [
													{ workflowRun: null },
													{
														workflowRun: {
															event: "pull_request",
															createdAt: "2026-10-24T10:05:00Z",
															workflow: { name: "Semgrep" },
														},
													},
													{
														workflowRun: {
															event: "pull_request",
															createdAt: "2026-10-24T10:06:00Z",
															workflow: { name: "CI/CD" },
														},
													},
												],
											},
										},
									},
								],
							},
							timelineItems: {
								nodes: [
									{ createdAt: "2026-10-24T10:41:00Z" },
									{ createdAt: "2026-10-24T10:45:00Z" },
								],
							},
						},
						{
							number: 6,
							title: "fix: b",
							createdAt: "2026-10-24T10:00:00Z",
							mergedAt: "2026-10-24T12:00:00Z",
							headRefName: "fix-b",
							mergeCommit: { oid: "c" },
							commits: { nodes: [] },
							head: { nodes: [] },
							timelineItems: { nodes: [] },
						},
					],
				},
			},
		},
	];
	const [version, ordinary] = parseChanges(pages, "changeset-release/main");
	assert.equal(version?.versionPr, true);
	assert.equal(version.firstCommitAt, Date.parse("2026-10-24T09:00:00Z"));
	// The push is when CI/CD started on the head; the gate is its last success; the queue is the last entry.
	assert.deepEqual(
		[version.pushedAt, version.greenAt, version.queuedAt, version.queueEntries],
		[
			Date.parse("2026-10-24T10:06:00Z"),
			Date.parse("2026-10-24T10:40:00Z"),
			Date.parse("2026-10-24T10:45:00Z"),
			2,
		],
	);
	// Without a listed commit, the change starts when its pull request opened.
	assert.equal(ordinary?.firstCommitAt, Date.parse("2026-10-24T10:00:00Z"));
	assert.deepEqual(
		[ordinary.pushedAt, ordinary.greenAt, ordinary.queueEntries],
		[undefined, undefined, 0],
	);
	assert.equal(searchCount(pages), 2);
	// A truncated list of check suites could hide the CI/CD run, so the report refuses it.
	const truncated = structuredClone(pages);
	const suites = truncated[0]?.data.search.nodes[0]?.head.nodes[0]?.commit.checkSuites;
	assert.ok(suites);
	suites.pageInfo.hasNextPage = true;
	assert.throws(() => parseChanges(truncated, "changeset-release/main"), /more check suites/u);
	assert.throws(() => parseChanges([{ data: {} }], "changeset-release/main"), /search/u);

	const releases = parseReleases([
		{
			data: {
				repository: {
					releases: {
						nodes: [
							{
								tagName: "v1.0.1",
								publishedAt: "2026-10-24T12:00:00Z",
								isDraft: false,
								isPrerelease: false,
								tagCommit: { oid: "c" },
							},
							{ tagName: "v1.1.0", publishedAt: null, isDraft: true, isPrerelease: false },
						],
					},
				},
			},
		},
	]);
	assert.deepEqual(releases, [
		{ tag: "v1.0.1", commit: "c", publishedAt: Date.parse("2026-10-24T12:00:00Z") },
	]);
});

await test("the Version PR branch follows the changeset base branch", () => {
	assert.equal(versionBranch({ baseBranch: "main" }), "changeset-release/main");
	assert.throws(() => versionBranch({ baseBranch: "" }), /no baseBranch/u);
});

await test("a deployment's outcome skips the inactive status that replaced it", () => {
	const parsed = parseDeployment("Staging", { sha: "b", created_at: "2026-10-24T12:00:00Z" }, [
		{ state: "inactive", created_at: "2026-10-24T13:00:00Z" },
		{ state: "success", created_at: "2026-10-24T12:04:00Z" },
		{ state: "in_progress", created_at: "2026-10-24T12:00:00Z" },
	]);
	assert.equal(parsed.outcome, "success");
	assert.equal(parsed.finishedAt, Date.parse("2026-10-24T12:04:00Z"));
	assert.equal(
		parseDeployment("Production", { sha: "b", created_at: "2026-10-24T12:00:00Z" }, []).outcome,
		undefined,
	);
});

await test("reverts are recognized from either revert title", () => {
	const metrics = deliveryMetrics(
		window,
		[change({ title: 'Revert "fix(server): keep feedback current"' })],
		[],
		[],
		position,
	);
	assert.equal(metrics.changes.revertRate, 1);
	assert.ok(isRevert("revert(release): defer v0.75.0"));
	assert.ok(!isRevert("fix(server): revert the cache to its old key"));
});

await test("a failed deployment of what runs is a failure, not a repeat", () => {
	const deployments = [
		deployment({ sha: "b", createdAt: 10 * MINUTE, finishedAt: 15 * MINUTE }),
		deployment({ sha: "b", createdAt: 20 * MINUTE, outcome: "error", finishedAt: 25 * MINUTE }),
		deployment({ sha: "b", createdAt: 30 * MINUTE, finishedAt: 35 * MINUTE }),
		deployment({ sha: "b", createdAt: 40 * MINUTE, finishedAt: 45 * MINUTE }),
		deployment({ sha: "b", createdAt: 50 * MINUTE, outcome: undefined, finishedAt: undefined }),
	];
	assert.deepEqual(
		[...classify(deployments, position).values()],
		["success", "failed", "success", "repeat", "repeat"],
	);
	const [staging] = deliveryMetrics(window, [], deployments, [], position).environments;
	assert.equal(staging?.changeFailRate, 1 / 3);
});

await test("moving back after a failed redeployment is a recovery, not a rollback of the build before", () => {
	const deployments = [
		deployment({ sha: "c", createdAt: 10 * MINUTE, finishedAt: 15 * MINUTE }),
		deployment({ sha: "c", createdAt: 20 * MINUTE, outcome: "failure", finishedAt: 25 * MINUTE }),
		deployment({ sha: "b", createdAt: 30 * MINUTE, finishedAt: 40 * MINUTE }),
	];
	assert.deepEqual([...classify(deployments, position).values()], ["success", "failed", "success"]);
	const [staging] = deliveryMetrics(window, [], deployments, [], position).environments;
	assert.ok(staging);
	assert.equal(staging.changeFailRate, 1 / 3);
	assert.equal(staging.recoveryTime.p50, 15 * MINUTE);
});

await test("redeploying the last good build after a failure is a recovery and rework, not a repeat", () => {
	const deployments = [
		deployment({ sha: "a", createdAt: 10 * MINUTE, finishedAt: 15 * MINUTE }),
		deployment({ sha: "b", createdAt: 20 * MINUTE, outcome: "failure", finishedAt: 25 * MINUTE }),
		deployment({ sha: "a", createdAt: 30 * MINUTE, finishedAt: 40 * MINUTE }),
	];
	assert.deepEqual([...classify(deployments, position).values()], ["success", "failed", "success"]);
	const [staging] = deliveryMetrics(window, [], deployments, [], position).environments;
	assert.ok(staging);
	assert.deepEqual(
		{
			recovered: staging.recoveryTime.count,
			p50: staging.recoveryTime.p50,
			open: staging.unrecovered,
			rework: staging.reworkRate,
		},
		{ recovered: 1, p50: 15 * MINUTE, open: 0, rework: 1 / 3 },
	);
});

await test("a deployment or release outside the first-parent history is counted, not measured", () => {
	const rendered = renderDeliveryMetrics(
		"Delivery metrics",
		deliveryMetrics(
			window,
			[],
			[deployment({ sha: "x" })],
			[{ tag: "v1.0.0", commit: "y", publishedAt: MINUTE }],
			position,
		),
	);
	assert.match(rendered, /1 release commits are not on the default branch/u);
	assert.match(rendered, /1 Staging deployments are not on the default branch/u);
});

await test("a deployment without a final status is reported, not counted", () => {
	const rendered = renderDeliveryMetrics(
		"Delivery metrics",
		deliveryMetrics(
			window,
			[],
			[deployment({ sha: "c", outcome: undefined, finishedAt: undefined })],
			[],
			position,
		),
	);
	assert.match(rendered, /1 Staging deployments have no final status yet and are not counted/u);
	// An undecided deployment of what runs is a repeat, and it still has no final status.
	const repeated = renderDeliveryMetrics(
		"Delivery metrics",
		deliveryMetrics(
			window,
			[],
			[
				deployment({ sha: "c", createdAt: MINUTE }),
				deployment({ sha: "c", createdAt: 2 * MINUTE, outcome: undefined, finishedAt: undefined }),
			],
			[],
			position,
		),
	);
	assert.match(repeated, /1 Staging deployments have no final status yet/u);
	assert.match(rendered, /\| Staging \| 0 \| 0\.0 \| 0 \| — \(0\/0\)/u);
});

await test("the report shows missing measurements as missing, not as zero", () => {
	const rendered = renderDeliveryMetrics(
		"Delivery metrics",
		deliveryMetrics(window, [], [], [], position),
	);
	assert.match(rendered, /\| Merged → running in production \| — \| — \| 0 \|/u);
	assert.match(rendered, /\| Production \| 0 \| 0\.0 \| 0 \| — \(0\/0\)/u);
	assert.equal(duration(90 * MINUTE), "90 min");
	assert.equal(duration(3 * 60 * MINUTE), "3.0 h");
	assert.equal(duration(72 * 60 * MINUTE), "3.0 d");
});

void test("the searches cover each day of the window once, a week at a time", () => {
	const until = Date.parse("2026-11-02T13:46:00Z");
	assert.deepEqual(mergedDateRanges(until - 28 * DAY, until), [
		"2026-10-05..2026-10-11",
		"2026-10-12..2026-10-18",
		"2026-10-19..2026-10-25",
		"2026-10-26..2026-11-01",
		"2026-11-02..2026-11-02",
	]);
	assert.deepEqual(mergedDateRanges(until - 3 * DAY, until), ["2026-10-30..2026-11-02"]);
});

/** A real failed child process, so these cases read the error that `gh` produces through `output`. */
const failingGh = async (stderr: string) =>
	output(process.execPath, ["-e", `console.error(${JSON.stringify(stderr)}); process.exit(1)`]);

/** Fails with each message in turn, then answers. */
function requests(...failures: string[]) {
	const state = { attempts: 0, waits: [] as number[] };
	return {
		state,
		request: async () => {
			const failure = failures[state.attempts];
			state.attempts += 1;
			return failure === undefined ? "answer" : failingGh(failure);
		},
		wait: async (milliseconds: number) => state.waits.push(milliseconds),
	};
}

void test("a transient GitHub failure is repeated with growing waits", async () => {
	for (const failure of [
		"gh: HTTP 502",
		"gh: Server Error (HTTP 503)",
		"gh: HTTP 504",
		"gh: You have exceeded a secondary rate limit. (HTTP 403)",
	]) {
		const { state, request, wait } = requests(failure, failure);
		assert.equal(await retryTransient(request, wait), "answer", failure);
		assert.deepEqual(state, { attempts: 3, waits: [10_000, 30_000] }, failure);
	}
});

void test("repeated transient failures stop at the bound and surface the last error", async () => {
	const failure = "gh: HTTP 504";
	const { state, request, wait } = requests(failure, failure, failure, failure, failure);
	await assert.rejects(retryTransient(request, wait), /HTTP 504/u);
	assert.deepEqual(state, { attempts: 4, waits: [10_000, 30_000, 60_000] });
});

void test("any other failure is not repeated", async () => {
	for (const failure of [
		"gh: Bad credentials (HTTP 401)",
		"gh: API rate limit exceeded for user. (HTTP 403)",
		"gh: Not Found (HTTP 404)",
		"gh: Validation Failed (HTTP 422)",
		"gh: Too Many Requests (HTTP 429)",
		"gh: Internal Server Error (HTTP 500)",
		"gh: Could not resolve to a Repository",
	]) {
		const { state, request, wait } = requests(failure);
		await assert.rejects(retryTransient(request, wait), /./u, failure);
		assert.deepEqual(state, { attempts: 1, waits: [] }, failure);
	}
	const parsing = { attempts: 0 };
	await assert.rejects(
		retryTransient(async () => {
			parsing.attempts += 1;
			throw new Error("HTTP 504 appears only in the message");
		}),
	);
	assert.equal(parsing.attempts, 1, "only stderr of the command decides");
});
