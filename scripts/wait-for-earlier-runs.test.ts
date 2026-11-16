import assert from "node:assert/strict";
import { test } from "node:test";

import { type Run, runsAhead, waitForTurn } from "./wait-for-earlier-runs.ts";

const run = (id: number, minute: number, status: string, extra: Partial<Run> = {}): Run => ({
	id,
	queuedAt: `2026-11-16T10:${String(minute).padStart(2, "0")}:00Z`,
	status,
	headSha: `sha-${String(id)}`,
	...extra,
});
const me = run(50, 30, "in_progress");
const noHeads = () => undefined;
const ids = (runs: Run[]) => runs.map(({ id }) => id);

await test("a run waits for every earlier run that is still active, oldest first", () => {
	const runs = [
		me,
		run(20, 20, "queued"),
		run(10, 10, "in_progress"),
		run(30, 25, "requested"),
		run(40, 26, "pending"),
		run(5, 5, "completed"),
		run(60, 40, "queued"),
	];
	assert.deepEqual(ids(runsAhead(me, runs, noHeads)), [10, 20, 30, 40]);
});

await test("runs queued in the same second go in the order of their run IDs", () => {
	const runs = [me, run(49, 30, "queued"), run(51, 30, "queued")];
	assert.deepEqual(ids(runsAhead(me, runs, noHeads)), [49]);
});

await test("a re-run queues behind the runs that were queued before its new attempt", () => {
	// Run 10 was created first, but its re-run was queued after this run.
	const rerun = run(10, 45, "queued");
	assert.deepEqual(ids(runsAhead(me, [me, rerun], noHeads)), []);
	assert.deepEqual(ids(runsAhead(rerun, [me, rerun], noHeads)), [50]);
});

await test("a run that waits for an approval never blocks the queue", () => {
	const runs = [me, run(10, 10, "waiting"), run(20, 20, "action_required")];
	assert.deepEqual(runsAhead(me, runs, noHeads), []);
});

await test("a pull-request run whose pull request moved to a new head is superseded", () => {
	const old = run(10, 10, "in_progress", { pullRequest: 7, headSha: "old" });
	const current = run(20, 20, "queued", { pullRequest: 8, headSha: "current" });
	const fork = run(30, 25, "queued");
	const heads = new Map([
		[7, "new"],
		[8, "current"],
	]);
	assert.deepEqual(
		ids(runsAhead(me, [me, old, current, fork], (pull) => heads.get(pull))),
		[20, 30],
	);
});

/** A wait on a fake clock, where each poll reads the next run list. */
function waiting(limitMs: number, lists: Run[][], heads = new Map<number, string>()) {
	let poll = 0;
	let clock = 0;
	const lines: string[] = [];
	const lookups: number[] = [];
	const done = waitForTurn({
		me,
		listRuns: async () => {
			const list = lists[Math.min(poll, lists.length - 1)] ?? [];
			poll += 1;
			return list;
		},
		headOf: async (pullRequest) => {
			lookups.push(pullRequest);
			return heads.get(pullRequest);
		},
		limitMs,
		pollMs: 60_000,
		now: () => clock,
		sleep: async (milliseconds) => {
			clock += milliseconds;
		},
		log: (line) => {
			lines.push(line);
		},
	});
	return { done, lines, lookups, elapsed: () => clock };
}

await test("the wait ends when the runs ahead end", async () => {
	const wait = waiting(300 * 60_000, [
		[me, run(10, 10, "in_progress"), run(20, 20, "queued")],
		[me, run(10, 10, "completed"), run(20, 20, "in_progress")],
		[me, run(10, 10, "completed"), run(20, 20, "completed")],
	]);
	assert.deepEqual(await wait.done, []);
	assert.equal(wait.lines.length, 2);
	assert.match(wait.lines[0] ?? "", /run 10\b/u);
	assert.match(wait.lines[1] ?? "", /run 20\b/u);
});

await test("after the limit the wait returns every run still ahead", async () => {
	const wait = waiting(2 * 60_000, [[me, run(10, 10, "in_progress"), run(20, 20, "queued")]]);
	const stillAhead = await wait.done;
	assert.deepEqual(ids(stillAhead), [10, 20]);
	assert.equal(wait.elapsed(), 2 * 60_000);
});

await test("a poll reads pull-request heads only up to the first run that blocks", async () => {
	const superseded = run(10, 10, "in_progress", { pullRequest: 7, headSha: "old" });
	const blocking = run(20, 20, "queued", { pullRequest: 8, headSha: "current" });
	const later = run(30, 25, "queued", { pullRequest: 9, headSha: "sha-30" });
	const finished = run(5, 5, "completed", { pullRequest: 6, headSha: "sha-5" });
	const wait = waiting(
		60_000,
		[
			[me, finished, superseded, blocking, later],
			[me, finished, superseded, run(20, 20, "completed"), run(30, 25, "completed")],
		],
		new Map([
			[7, "new"],
			[8, "current"],
		]),
	);
	assert.deepEqual(await wait.done, []);
	assert.deepEqual(wait.lookups, [7, 8, 7]);
});
