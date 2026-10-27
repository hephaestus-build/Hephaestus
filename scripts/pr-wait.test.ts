import assert from "node:assert/strict";
import { test } from "node:test";

import {
	type Check,
	evaluate,
	type Memory,
	type Note,
	parse,
	parseTarget,
	type Snapshot,
} from "./pr-wait.ts";

const gate: Check = { name: "CI Status Gate", state: "pass", url: "https://ci/1" };

function snapshot(overrides: Partial<Snapshot> = {}): Snapshot {
	return {
		state: "OPEN",
		isDraft: false,
		url: "https://github.com/o/r/pull/7",
		head: "abcdef1234",
		mergeState: "CLEAN",
		reviewDecision: "REVIEW_REQUIRED",
		mine: new Set(["author"]),
		notes: [],
		unresolved: [],
		checks: [gate],
		requiredNames: ["CI Status Gate"],
		...overrides,
	};
}

const fresh: Memory = { seen: new Set(), first: false };

/** Two polls of one snapshot: the second sees the checks unchanged, so it may call them done. */
const settled = (pr: Snapshot) => evaluate(pr, evaluate(pr, fresh).memory);

const note = (id: string, author: string, kind: Note["kind"] = "comment"): Note => ({
	id,
	kind,
	author,
	url: `https://github.com/o/r/pull/7#${id}`,
});

await test("the first poll records what exists and reports nothing", () => {
	const pr = snapshot({ notes: [note("c1", "reviewer")], checks: [] });
	const first = evaluate(pr, { seen: new Set(), first: true });
	assert.deepEqual(first.lines, []);
	assert.deepEqual(evaluate(pr, first.memory).lines, []);
});

await test("a comment from someone else is reported once, by kind, author and URL only", () => {
	const pr = snapshot({ notes: [note("c1", "reviewer"), note("c2", "author")], checks: [] });
	const first = evaluate(pr, fresh);
	assert.deepEqual(first.lines, ["comment from reviewer: https://github.com/o/r/pull/7#c1"]);
	assert.deepEqual(evaluate(pr, first.memory).lines, []);
});

await test("a CI bot's comment is not news, but its review thread is", () => {
	const pr = snapshot({
		notes: [note("c1", "github-actions"), note("t1", "coderabbitai", "thread")],
		checks: [],
	});
	assert.deepEqual(evaluate(pr, fresh).lines, [
		"thread from coderabbitai: https://github.com/o/r/pull/7#t1",
	]);
});

await test("checks are not done until two polls agree", () => {
	assert.equal(evaluate(snapshot({ reviewDecision: "APPROVED" }), fresh).exit, undefined);
});

await test("green required checks, an approval and no open thread are ready", () => {
	const verdict = settled(snapshot({ reviewDecision: "APPROVED" }));
	assert.equal(verdict.exit, 0);
	assert.deepEqual(verdict.lines, ["ready on abcdef1: review approved"]);
});

await test("green checks without an approval keep waiting, because a reviewer must act", () => {
	const verdict = settled(snapshot());
	assert.equal(verdict.exit, undefined);
	assert.deepEqual(verdict.lines, ["required checks green on abcdef1: review review_required"]);
});

await test("a draft, changes requested, or an unresolved thread on a green head need the agent", () => {
	assert.equal(settled(snapshot({ isDraft: true, reviewDecision: "APPROVED" })).exit, 1);
	assert.equal(settled(snapshot({ reviewDecision: "CHANGES_REQUESTED" })).exit, 1);
	const thread = settled(snapshot({ reviewDecision: "APPROVED", unresolved: ["https://t/1"] }));
	assert.deepEqual(
		[thread.exit, thread.lines],
		[1, ["unresolved threads on green abcdef1: https://t/1"]],
	);
});

await test("a failed required check stops at once; a failed optional one is an event only", () => {
	const failed = evaluate(snapshot({ checks: [{ ...gate, state: "fail" }] }), fresh);
	assert.deepEqual(
		[failed.exit, failed.lines],
		[1, ["check failed: CI Status Gate https://ci/1", "required check failed: CI Status Gate"]],
	);

	const optional: Check = { name: "Preview", state: "fail", url: "https://ci/2" };
	const pr = snapshot({
		checks: [gate, optional],
		reviewDecision: "APPROVED",
		mergeState: "UNSTABLE",
	});
	const first = evaluate(pr, fresh);
	assert.deepEqual([first.exit, first.lines], [undefined, ["check failed: Preview https://ci/2"]]);
	assert.equal(evaluate(pr, first.memory).exit, 0);
});

await test("a required check that has not registered yet holds the watch, even when approved", () => {
	const pr = snapshot({
		requiredNames: ["CI Status Gate", "Actionlint"],
		reviewDecision: "APPROVED",
	});
	assert.equal(settled(pr).exit, undefined);
});

await test("approved and green is not ready while GitHub still calls the merge blocked", () => {
	assert.equal(
		settled(snapshot({ reviewDecision: "APPROVED", mergeState: "BLOCKED" })).exit,
		undefined,
	);
});

await test("a stacked pull request gates on every check it has", () => {
	const build: Check = { name: "Build", state: "pass", url: "" };
	const stacked = (last: Check["state"]) =>
		snapshot({
			requiredNames: [],
			checks: [build, { name: "Test", state: last, url: "https://ci/3" }],
			reviewDecision: "APPROVED",
		});
	assert.equal(settled(stacked("pending")).exit, undefined);
	assert.equal(settled(stacked("pass")).exit, 0);
	assert.equal(evaluate(stacked("fail"), fresh).exit, 1);
	assert.equal(settled(snapshot({ requiredNames: [], checks: [] })).exit, undefined);
});

await test("a conflict stops; behind its base alone does not", () => {
	assert.equal(evaluate(snapshot({ mergeState: "DIRTY" }), fresh).exit, 1);
	assert.equal(settled(snapshot({ mergeState: "BEHIND", reviewDecision: "APPROVED" })).exit, 0);
});

await test("a merged pull request ends the watch successfully, a closed one does not", () => {
	assert.equal(evaluate(snapshot({ state: "MERGED" }), fresh).exit, 0);
	assert.equal(evaluate(snapshot({ state: "CLOSED" }), fresh).exit, 1);
});

const connection = (nodes: unknown[], totalCount = nodes.length) => ({ totalCount, nodes });

const response = (pullRequest: Record<string, unknown>) => ({
	data: {
		viewer: { login: "me" },
		repository: {
			pullRequest: {
				url: "https://github.com/o/r/pull/7",
				state: "OPEN",
				isDraft: false,
				headRefOid: "abcdef1234",
				mergeStateStatus: "CLEAN",
				reviewDecision: null,
				author: { login: "author" },
				commits: connection([]),
				comments: connection([]),
				reviews: connection([]),
				reviewThreads: connection([]),
				...pullRequest,
			},
		},
	},
});

const threadComment = (id: string) => ({ id, url: `https://t/${id}`, author: { login: "rev" } });

await test("a check run and a commit status map to pass, fail or pending; the rules name the required", () => {
	const contexts = connection([
		{
			name: "CI Status Gate",
			status: "COMPLETED",
			conclusion: "SKIPPED",
			detailsUrl: "u",
		},
		{ name: "Build", status: "IN_PROGRESS", conclusion: null, detailsUrl: "u" },
		{
			name: "Zizmor",
			status: "COMPLETED",
			conclusion: "TIMED_OUT",
			detailsUrl: "u",
		},
		{ context: "deploy", state: "EXPECTED", targetUrl: null },
	]);
	const commits = connection([{ commit: { statusCheckRollup: { contexts } } }], 30);
	const rules = connection([
		{ parameters: {} },
		{
			parameters: { requiredStatusChecks: [{ context: "CI Status Gate" }, { context: "Zizmor" }] },
		},
	]);
	const pr = parse(response({ commits, baseRef: { rules } }));
	assert.deepEqual(pr.requiredNames, ["CI Status Gate", "Zizmor"]);
	assert.deepEqual(
		pr.checks.map(({ name, state }) => [name, state]),
		[
			["CI Status Gate", "pass"],
			["Build", "pending"],
			["Zizmor", "fail"],
			["deploy", "pending"],
		],
	);
});

await test("an unresolved thread is collected, and an empty COMMENTED review is not a note", () => {
	const pr = parse(
		response({
			reviews: connection([{ id: "r1", url: "u", state: "COMMENTED", body: "", author: null }]),
			reviewThreads: connection([
				{ isResolved: false, comments: connection([threadComment("a"), threadComment("b")]) },
			]),
		}),
	);
	assert.deepEqual(pr.unresolved, ["https://t/a"]);
	assert.deepEqual(
		pr.notes.map(({ kind }) => kind),
		["thread", "reply"],
	);
});

await test("a page that does not hold every thread stops the watch instead of reading as none", () => {
	assert.throws(
		() => parse(response({ reviewThreads: connection([], 101) })),
		/reviewThreads has 101 entries, more than one page/u,
	);
});

await test("a target is a number, #number, or pull request URL", () => {
	assert.deepEqual(parseTarget("42"), { number: 42 });
	assert.deepEqual(parseTarget("#42"), { number: 42 });
	assert.deepEqual(parseTarget("https://github.com/o/r/pull/42/files"), {
		number: 42,
		repo: "o/r",
	});
	assert.throws(() => parseTarget(""), /not a pull request number/u);
	assert.throws(() => parseTarget("0"), /not a pull request number/u);
});
