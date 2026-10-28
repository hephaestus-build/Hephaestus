import assert from "node:assert/strict";
import { test } from "node:test";

import { UnratedError, type Evidence } from "../../lib/core/practice-script.ts";
import {
	readGeneralComments,
	readLinkedWorkItemCapture,
	readReviewComments,
	readReviewThreads,
	readPullRequestChecks,
} from "./review.ts";

const evidence = (value: unknown): Pick<Evidence, "record"> => ({
	record: async () => ({ status: "FOUND", text: JSON.stringify(value) }),
});

const readers = [
	{
		name: "linked_work_items.json",
		read: readLinkedWorkItemCapture,
		empty: { workItems: [], unresolvedReferences: [] },
		result: { items: [], unresolved: [] },
		invalid: [
			[],
			{},
			{ workItems: null },
			{ workItems: [null] },
			{ workItems: [{ number: "1", title: "Issue", body: "" }] },
			{ workItems: [{ number: 1, title: "Issue" }] },
			{ workItems: [{ number: 1, title: "Issue", body: "", subIssuesSource: "everything" }] },
			{ workItems: [], unresolvedReferences: ["1"] },
		],
	},
	{
		name: "comments.json",
		read: readReviewComments,
		empty: [],
		result: [],
		invalid: [
			{},
			{ comments: [] },
			[null],
			[{ path: "src/a.ts" }],
			[{ path: "src/a.ts", body: 1 }],
		],
	},
	{
		name: "general_comments.json",
		read: readGeneralComments,
		empty: { comments: [] },
		result: [],
		invalid: [
			[],
			{},
			{ comments: null },
			{ comments: [null] },
			{ comments: [{}] },
			{ comments: [{ body: "Ask", bot: "false" }] },
		],
	},
	{
		name: "review_threads.json",
		read: readReviewThreads,
		empty: { threads: [], reviewDecisions: [] },
		result: { threads: [], decisions: [], reviewDecision: undefined },
		invalid: [
			[],
			{},
			{ threads: [], reviewDecisions: {} },
			{ threads: [null], reviewDecisions: [] },
			{ threads: [{}], reviewDecisions: [] },
			{ threads: [{ state: "RESOLVED", line: "7" }], reviewDecisions: [] },
			{ threads: [], reviewDecisions: [{}] },
			{ threads: [], reviewDecisions: [{ state: "APPROVED", author: false }] },
		],
	},
];

void test("review decisions preserve the retained read time separately from provider submission time", async () => {
	const decision = {
		state: "CHANGES_REQUESTED",
		author: "reviewer",
		body: "Check the error path.",
		submittedAt: "2026-10-20T10:00:00Z",
		observedAt: "2026-10-21T11:00:00Z",
	};
	assert.deepEqual(
		await readReviewThreads(evidence({ threads: [], reviewDecisions: [decision] })),
		{
			threads: [],
			reviewDecision: undefined,
			decisions: [{ ...decision, bot: false }],
		},
	);
});

for (const { name, read, empty, result, invalid } of readers) {
	void test(`${name} distinguishes absent capture from measured empty data`, async () => {
		assert.equal(await read({ record: async () => ({ status: "ABSENT" }) }), null);
		assert.deepEqual(await read(evidence(empty)), result);
	});
	void test(`${name} rejects wrong envelopes and malformed entries as unavailable`, async () => {
		for (const value of [null, true, "record", ...invalid]) {
			await assert.rejects(read(evidence(value)), (error: unknown) => {
				assert.ok(error instanceof UnratedError);
				assert.equal(error.reason, "unavailable");
				assert.ok(error.message.includes(name));
				return true;
			});
		}
	});
	void test(`${name} rejects malformed JSON as unavailable`, async () => {
		await assert.rejects(
			read({ record: async () => ({ status: "FOUND", text: "{" }) }),
			(error: unknown) => {
				assert.ok(error instanceof UnratedError);
				assert.equal(error.reason, "unavailable");
				assert.equal(error.message, `${name} is not valid JSON`);
				return true;
			},
		);
	});
}

void test("comment projection preserves captured body lines and omitted provider fields", async () => {
	const record = {
		record: async () => ({
			status: "FOUND" as const,
			text: '[\n  {\n    "path": "src/a.ts",\n    "body": "Please fix this"\n  }\n]',
		}),
	};
	assert.deepEqual(await readReviewComments(record), [
		{
			path: "src/a.ts",
			body: "Please fix this",
			recordLine: 4,
			outdated: false,
			bot: false,
			id: undefined,
			thread: undefined,
			inReplyTo: undefined,
			line: undefined,
			side: undefined,
			author: undefined,
			createdAt: undefined,
		},
	]);
});

void test("the checks reader preserves unknown runs and does not turn an absent capture into an empty result", async () => {
	assert.equal(await readPullRequestChecks({ record: async () => ({ status: "ABSENT" }) }), null);
	const checks = {
		basis: { other_fields: "CAPTURE" },
		commit_sha: "a".repeat(40),
		head_checks: "PENDING",
	};
	assert.deepEqual(await readPullRequestChecks(evidence(checks)), checks);
	await assert.rejects(
		readPullRequestChecks(evidence({ ...checks, head_checks: "unknown" })),
		UnratedError,
	);
});

void test("the discussion reader preserves the provider aggregate independently of submitted decisions", async () => {
	const read = await readReviewThreads(
		evidence({ threads: [], reviewDecisions: [], review_decision: "APPROVED" }),
	);
	assert.deepEqual(read, { threads: [], decisions: [], reviewDecision: "APPROVED" });
});
