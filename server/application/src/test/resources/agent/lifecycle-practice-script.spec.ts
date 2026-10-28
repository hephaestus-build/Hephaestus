import assert from "node:assert/strict";
import test from "node:test";

import type { DiffFile } from "../../../../../../docker/agents/practice-scripts/kits/scm/types.ts";
import { REVIEW_LEAD_LIMIT } from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import { isJsonObject } from "../../../../../../docker/agents/practice-scripts/lib/core/json.ts";
import { UnratedError } from "../../../../../../docker/agents/practice-scripts/lib/core/practice-script.ts";
import {
	leadKeys,
	event,
	requested,
	runBundled,
	runBundledProgram,
	type Files,
	type RunInputs,
} from "../../../../../../docker/agents/practice-scripts/run-bundled.ts";

const metadata = {
	pr_number: 16,
	pr_url: "https://example.org/team/project/pull/16",
	repository_full_name: "team/project",
	repository_id: 1,
	provider_id: 1,
	source_branch: "18-add-ingredient-view",
	target_branch: "main",
	title: "#18: Add ingredient creation view",
	body: "Closes #18\n\nAdds the view.",
	state: "MERGED",
	author: "ada",
	merged_by: "ada",
	created_at: "2026-04-13T10:16:13Z",
	merged_at: "2026-04-13T15:06:54Z",
};
const { merged_at: _mergedAt, merged_by: _mergedBy, ...unmerged } = metadata;

function diffFile(path: string, added: number[]): [string, DiffFile] {
	return [
		path,
		{
			path,
			addedLines: new Map(added.map((line) => [line, "changed"])),
			removedLines: new Map(),
			hunks: [],
		},
	];
}

interface CapturedCommit {
	sha: string;
	message: string;
	authoredAt: string;
	files?: { status: string; path: string }[];
}

function commitsRecord(commits: CapturedCommit[]) {
	return {
		commits: commits.map((c) => ({
			files: [],
			...c,
			sha: c.sha.padEnd(40, "a"),
			author: "ada",
			committer: "ada",
			committedAt: c.authoredAt,
			parents: [],
		})),
	};
}

function stagedRecords(itemRecord: Record<string, unknown>, given: Files): Files {
	const linked = given["linked_work_items.json"];
	const items: unknown[] =
		isJsonObject(linked) && Array.isArray(linked.workItems) ? linked.workItems : [];
	const files: Files = {
		"metadata.json": itemRecord,
		"description.md": typeof itemRecord.body === "string" ? itemRecord.body : "",
	};
	for (const item of items) {
		if (isJsonObject(item) && typeof item.number === "number") {
			files[`linked_work_items/${String(item.number)}.md`] = `# ${String(item.title)}\n`;
		}
	}
	return files;
}

async function review(slug: string, inputs: RunInputs) {
	const given = inputs.context ?? {};
	const result = await runBundled(slug, {
		contextReference: "areas/changed-item",
		...inputs,
		context:
			typeof given === "string" ? given : { ...stagedRecords(inputs.item ?? {}, given), ...given },
	});
	return { ...result, leads: result.leads ?? [] };
}

const atMerge = event("scm.pull_request.merged");

const requestedOpen = requested("scm.pull_request", { state: "OPEN" });

const requestedMerged = requested("scm.pull_request", { state: "MERGED" });

void test("malformed review comments stop the bundled program before it can claim no asks", async () => {
	const result = await runBundledProgram("defers-review-asks-into-tracked-work", {
		item: metadata,
		inputs: atMerge,
		context: {
			"comments.json": [{ path: "src/cart.ts", body: null }],
			"general_comments.json": { comments: [] },
		},
	});
	assert.deepEqual(result.reviews, []);
	assert.ok("error" in result.outcome);
	const { error } = result.outcome;
	assert.ok(error instanceof UnratedError);
	assert.equal(error.reason, "unavailable");
	assert.match(error.message, /comments\.json/u);
});

for (const slug of [
	"merge-confirms-the-linked-issue-outcome",
	"plans-the-work-in-an-issue-first",
]) {
	for (const count of [199, 200, 201]) {
		void test(`${slug} retains totals and bounds the leads for ${count} linked issues`, async () => {
			const result = await review(slug, {
				item: metadata,
				inputs: atMerge,
				context: {
					"linked_work_items.json": {
						workItems: Array.from({ length: count }, (_, i) => ({
							number: i + 1,
							title: `Issue ${i + 1}`,
							body: "- [ ] Confirm the outcome",
							how: "closesOnMerge",
							createdAt: i === count - 1 ? "2026-04-13T10:00:00Z" : "2026-04-13T08:00:00Z",
						})),
					},
					"commits.json": commitsRecord([
						{
							sha: "f46a897aaaa",
							message: "Implement the plan",
							authoredAt: "2026-04-13T09:00:00Z",
						},
					]),
				},
			});
			assert.equal(result.leads.length, Math.min(count, REVIEW_LEAD_LIMIT));
			assert.equal(result.facts?.linkedItems, count);
			if (slug === "merge-confirms-the-linked-issue-outcome") {
				assert.equal(result.facts.checkableItems, count);
				assert.equal(result.facts.untickedItems, count);
			} else {
				assert.equal(result.facts.namedIssues, count);
				assert.equal(result.facts.issueOpenedAfterFirstCommitMinutes, 60);
			}
			assert.deepEqual(
				result.limitations,
				count > REVIEW_LEAD_LIMIT
					? [{ what: "linked issue leads", count: count - REVIEW_LEAD_LIMIT, reason: "selection" }]
					: [],
			);
		});
	}
}

for (const count of [99, 100, 201]) {
	void test(`approval preparation retains totals and bounds the leads for ${count} decisions`, async () => {
		const result = await review("merges-only-after-approval", {
			item: metadata,
			inputs: atMerge,
			context: {
				"review_threads.json": {
					threads: [],
					reviewDecisions: Array.from({ length: count }, (_, i) => ({
						state: "APPROVED",
						author: `reviewer-${i}`,
						submittedAt: "2026-04-13T14:00:00Z",
						bot: false,
						dismissed: false,
					})),
				},
			},
		});
		const total = 1 + count * 2;
		assert.equal(result.leads.length, Math.min(total, REVIEW_LEAD_LIMIT));
		assert.equal(result.facts?.decisions, count);
		assert.equal(result.facts.approvalsBeforeMergeByOthers, count);
		assert.deepEqual(
			result.limitations,
			total > REVIEW_LEAD_LIMIT
				? [{ what: "review decision leads", count: total - REVIEW_LEAD_LIMIT, reason: "selection" }]
				: [],
		);
	});
}

for (const count of [199, 200, 250]) {
	void test(`thread preparation retains totals and bounds the leads for ${count} threads`, async () => {
		const result = await review("merged-past-unresolved-review-threads", {
			item: metadata,
			inputs: atMerge,
			context: {
				"review_threads.json": {
					reviewDecisions: [],
					threads: Array.from({ length: count }, (_, id) => ({
						id,
						path: "src/cart.ts",
						line: 2,
						state: "UNRESOLVED",
						createdAt: "2026-04-13T14:00:00Z",
					})),
				},
			},
		});
		assert.equal(result.leads.length, Math.min(count + 1, REVIEW_LEAD_LIMIT));
		assert.equal(result.facts?.threads, count);
		assert.equal(result.facts.candidateThreads, count);
		assert.deepEqual(
			result.limitations,
			count + 1 > REVIEW_LEAD_LIMIT
				? [
						{
							what: "review thread leads",
							count: count + 1 - REVIEW_LEAD_LIMIT,
							reason: "selection",
						},
					]
				: [],
		);
	});
}

void test("malformed linked item JSON stops preparation instead of reporting an absent capture", async () => {
	await assert.rejects(
		review("honours-linked-issue-acceptance-criteria", {
			item: metadata,
			inputs: requestedMerged,
			context: { "linked_work_items.json": "{" },
		}),
		{
			name: "UnratedError",
			reason: "unavailable",
			message: "linked_work_items.json is not valid JSON",
		},
	);
});

void test("plans-first puts the issue's opening beside the earliest commit, never beside the pull request", async () => {
	const result = await review("plans-the-work-in-an-issue-first", {
		item: metadata,
		context: {
			"linked_work_items.json": {
				workItems: [
					{ number: 18, title: "Add ingredients", createdAt: "2026-04-13T09:35:24Z", body: "" },
					{ number: 12, title: "Unrelated", createdAt: "2026-04-01T00:00:00Z", body: "" },
				],
			},
			"commits.json": commitsRecord([
				{
					sha: "f46a897aaaa",
					message: "Add AddIngredientView",
					authoredAt: "2026-04-12T17:11:59Z",
				},
				{ sha: "dd6e2f5bbbb", message: "Wire it", authoredAt: "2026-04-12T17:38:00Z" },
			]),
		},
	});
	const keyOf = await leadKeys("plans-the-work-in-an-issue-first");
	assert.equal(result.leads.length, 1);
	const [lead] = result.leads;
	assert.ok(lead);
	assert.deepEqual(lead.at, { record: "linked_work_items/18.md", line: 0 });
	assert.equal(keyOf(lead.label), "candidate-issue");
	assert.equal(lead.facts?.number, 18);
	assert.equal(lead.facts.firstCommit, "f46a897");
	assert.equal(lead.facts.hoursFromIssueToFirstCommit, "-16.4");
	assert.match(
		String(lead.facts.relation),
		/the issue was opened 16\.4 h after the earliest commit was authored/u,
	);
	assert.equal(result.facts?.issueOpenedAfterFirstCommitMinutes, 983);
	assert.match(result.directions?.[0] ?? "", /never against the pull request's creation/u);
});

void test("plans-first preserves the exact recorded order before, at, and after the first commit", async () => {
	for (const [openedAt, hours] of [
		["2026-04-12T17:00:00Z", "1.0"],
		["2026-04-12T18:00:00Z", "0.0"],
		["2026-04-12T18:30:00Z", "-0.5"],
	]) {
		const result = await review("plans-the-work-in-an-issue-first", {
			item: metadata,
			context: {
				"linked_work_items.json": {
					workItems: [{ number: 18, title: "Add ingredients", body: "", createdAt: openedAt }],
				},
				"commits.json": commitsRecord([
					{ sha: "f46a897aaaa", message: "Add ingredients", authoredAt: "2026-04-12T18:00:00Z" },
				]),
			},
		});
		const lead = result.leads[0];
		assert.ok(lead);
		assert.equal(lead.facts?.openedAt, openedAt);
		assert.equal(lead.facts?.firstCommitAuthoredAt, "2026-04-12T18:00:00Z");
		assert.equal(lead.facts.hoursFromIssueToFirstCommit, hours);
	}
});

void test("plans-first cannot replace unknown authored dates with a later handoff", async () => {
	for (const commits of [
		[{ sha: "f46a897aaaa", message: "Add ingredients", authoredAt: "" }],
		[{ sha: "f46a897aaaa", message: "Add ingredients", authoredAt: "not-a-date" }],
		[
			{ sha: "f46a897aaaa", message: "Add ingredients", authoredAt: "" },
			{ sha: "dd6e2f5bbbb", message: "Wire it", authoredAt: "2026-04-12T17:38:00Z" },
		],
	]) {
		const result = await review("plans-the-work-in-an-issue-first", {
			item: metadata,
			context: {
				"linked_work_items.json": {
					workItems: [
						{ number: 18, title: "Add ingredients", body: "", createdAt: "2026-04-13T09:35:24Z" },
					],
				},
				"commits.json": commitsRecord(commits),
			},
		});
		assert.equal(result.leads.length, 1);
		const facts = result.leads[0]?.facts;
		assert.match(
			String(facts?.relation),
			/do not establish the earliest authored time; the order is not known from the record/u,
		);
		assert.equal(facts?.firstCommit, undefined);
		assert.equal(facts?.hoursFromIssueToFirstCommit, undefined);
		assert.equal(facts?.hoursFromIssueToPullRequest, undefined);
		const directions = (result.directions ?? []).join("\n");
		assert.match(directions, /creation dates the handoff, not the start of work/u);
		assert.match(directions, /Missing commit capture is a collection gap, not an unsettled order/u);
	}
});

void test("plans-first keeps missing commit capture separate from unknown authored dates", async () => {
	for (const malformed of [undefined, "{", '{"commits":[null]}']) {
		const result = await review("plans-the-work-in-an-issue-first", {
			item: metadata,
			context: {
				"linked_work_items.json": {
					workItems: [
						{ number: 18, title: "Add ingredients", body: "", createdAt: "2026-04-13T09:35:24Z" },
					],
				},
				...(malformed === undefined ? {} : { "commits.json": malformed }),
			},
		});
		assert.match(
			(result.directions ?? []).join("\n"),
			/report a collection gap and record no problem\./u,
		);
		const facts = result.leads[0]?.facts;
		assert.match(String(facts?.relation), /commit record is missing or malformed/u);
		assert.equal(facts?.firstCommit, undefined);
	}
});

const partial = {
	...unmerged,
	source_branch: "tutor-question-draft",
	title: "Add a persistent tutor question draft",
	created_at: "2026-09-27T02:19:18Z",
};

const partialCommits = commitsRecord([
	{ sha: "b08d591eaaaa", message: "Save the draft", authoredAt: "2026-09-27T02:18:53Z" },
]);

function linkedIssue(number: number, createdAt: string, how: "closesOnMerge" | "mentions") {
	return { number, title: `Issue ${String(number)}`, state: "OPEN", how, createdAt, body: "" };
}

void test("plans-first dates an issue named only by Related to as a mention, before or after the item began", async () => {
	for (const [openedAt, relation] of [
		["2026-09-27T02:01:31Z", /the earliest commit was authored 0\.3 h after the issue was opened/u],
		["2026-09-27T05:18:53Z", /the issue was opened 3\.0 h after the earliest commit was authored/u],
	] as const) {
		const result = await review("plans-the-work-in-an-issue-first", {
			item: { ...partial, body: "Related to #5\n\nFirst slice only; #5 stays open." },
			context: {
				"linked_work_items.json": { workItems: [linkedIssue(5, openedAt, "mentions")] },
				"commits.json": partialCommits,
			},
		});
		const [lead] = result.leads;
		assert.ok(lead);
		assert.equal(result.leads.length, 1);
		assert.equal(lead.facts?.namedBy, "Related to");
		assert.equal(lead.facts.providerLink, "mentions");
		assert.match(String(lead.facts.relation), relation);
	}
});

void test("plans-first names no candidate for a bare, quoted or fenced number, nor without the capture", async () => {
	for (const body of [
		"See also #5 for the sharing item.",
		"> Related to #5",
		"```\nRelated to #5\n```",
		"```\nCloses #5\n```",
		"> Fixes #5",
	]) {
		const result = await review("plans-the-work-in-an-issue-first", {
			item: { ...partial, body },
			context: {
				"linked_work_items.json": {
					workItems: [linkedIssue(5, "2026-09-27T02:01:31Z", "mentions")],
				},
				"commits.json": partialCommits,
			},
		});
		assert.deepEqual(result.leads, [], body);
		assert.equal(result.facts?.namedIssues, 0, body);
	}
	const uncaptured = await review("plans-the-work-in-an-issue-first", {
		item: { ...partial, body: "Related to #5" },
		context: { "commits.json": partialCommits },
	});
	assert.deepEqual(uncaptured.leads, []);
	assert.match(uncaptured.directions?.[0] ?? "", /was not captured: a collection gap/u);
});

void test("plans-first dates a provider closing link no text names, and lists it apart from a related issue", async () => {
	const result = await review("plans-the-work-in-an-issue-first", {
		item: { ...partial, body: "Related to #5" },
		context: {
			"linked_work_items.json": {
				workItems: [
					linkedIssue(4, "2026-09-27T05:00:00Z", "closesOnMerge"),
					linkedIssue(5, "2026-09-27T02:01:31Z", "mentions"),
				],
			},
			"commits.json": partialCommits,
		},
	});
	assert.deepEqual(
		result.leads.map((lead) => [lead.facts?.number, lead.facts?.namedBy, lead.facts?.providerLink]),
		[
			[4, "provider closing link", "closesOnMerge"],
			[5, "Related to", "mentions"],
		],
	);
	assert.match((result.directions ?? []).join("\n"), /do not choose the favourable one/u);
});

void test("plans-first leaves an unresolved or undated named issue open to the description, and still dates the rest", async () => {
	const closing = linkedIssue(4, "2026-09-27T01:00:00Z", "closesOnMerge");
	const result = await review("plans-the-work-in-an-issue-first", {
		item: { ...partial, body: "Related to #5" },
		context: {
			"linked_work_items.json": { workItems: [closing], unresolvedReferences: [5] },
			"commits.json": partialCommits,
		},
	});
	assert.equal(result.facts?.unresolvedNamedIssues, 1);
	assert.deepEqual(
		result.leads.map((lead) => lead.facts?.number),
		[4],
	);
	assert.match(
		(result.directions ?? []).join(" "),
		/#5 are among the capture's unresolved references.*if it is, the record cannot settle the order, so name that issue.*if the description rules it out, it is no applicability/u,
	);
	const { createdAt: _openedAt, ...undated } = linkedIssue(5, "", "mentions");
	const undatedResult = await review("plans-the-work-in-an-issue-first", {
		item: { ...partial, body: "Related to #5" },
		context: {
			"linked_work_items.json": { workItems: [undated] },
			"commits.json": partialCommits,
		},
	});
	const [lead] = undatedResult.leads;
	assert.match(String(lead?.facts?.relation), /its opening time is not captured/u);
	for (const envelope of [{ unresolvedReferences: [9] }, { truncated: true }]) {
		const unaffected = await review("plans-the-work-in-an-issue-first", {
			item: { ...partial, body: "Related to #5" },
			context: {
				"linked_work_items.json": {
					workItems: [closing, linkedIssue(5, "2026-09-27T02:01:31Z", "mentions")],
					...envelope,
				},
				"commits.json": partialCommits,
			},
		});
		assert.equal(unaffected.facts?.unresolvedNamedIssues, 0);
		assert.equal(unaffected.leads.length, 2);
	}
});

void test("deferred asks are one lead each, with the record's facts and none of the judgement", async () => {
	const context = {
		"comments.json": [
			{
				path: "App/AddIngredientView.swift",
				line: 55,
				body: "Maybe a visual indication when the stock value is wrong?",
				author: "jennifer",
				created_at: "2026-04-13T14:18:20Z",
			},
			{
				path: "App/AddIngredientView.swift",
				line: 12,
				body: "Use .textInputAutocapitalization(.words)",
				author: "jennifer",
				created_at: "2026-04-13T14:19:00Z",
			},
			{
				path: "App/AddIngredientView.swift",
				line: 12,
				body: "Done",
				author: "ada",
				created_at: "2026-04-13T14:40:00Z",
			},
			{
				path: "App/AddIngredientView.swift",
				line: 55,
				body: "Ignore this, the validation is out of scope",
				author: "jennifer",
				created_at: "2026-04-13T14:45:00Z",
			},
		],
		"general_comments.json": {
			comments: [
				{
					author: "jennifer",
					body: "Please add the empty state in your next MR",
					createdAt: "2026-04-13T14:50:00Z",
				},
				{ author: "ada", body: "Will do, tracked in #21", createdAt: "2026-04-13T14:55:00Z" },
			],
		},
		"review_threads.json": {
			threads: [{ path: "App/AddIngredientView.swift", line: 55, state: "RESOLVED" }],
			reviewDecisions: [
				{ state: "APPROVED", author: "jennifer", submittedAt: "2026-04-13T14:52:00Z" },
			],
		},
	};
	const result = await review("defers-review-asks-into-tracked-work", {
		item: { ...metadata, is_merged: true },
		inputs: atMerge,
		change: new Map([diffFile("App/AddIngredientView.swift", [12, 13])]),
		context,
	});
	const keyOf = await leadKeys("defers-review-asks-into-tracked-work");
	assert.equal(result.facts?.asks, 3);
	assert.equal(result.facts.inlineAsks, 2);
	assert.equal(result.facts.conversationAsks, 1);
	const [stock, capitalisation, emptyState] = result.leads;
	assert.ok(stock && capitalisation && emptyState);
	assert.equal(keyOf(stock.label), "inline-ask");
	assert.deepEqual(stock.at, { record: "comments.json", line: 0 });
	assert.equal(stock.facts?.path, "App/AddIngredientView.swift");
	assert.equal(stock.facts.line, 55);
	assert.equal(stock.facts.fileInChange, true);
	assert.equal(stock.facts.changeNearLine, false);
	assert.equal(stock.facts.authorReplied, false);
	assert.equal(stock.facts.threadResolved, true);
	assert.equal(stock.facts.reviewerFollowedUp, true);
	assert.equal(stock.facts.waiverWordsInFollowUp, true);
	assert.equal(stock.facts.approvedAfterAsk, true);
	assert.equal(capitalisation.facts?.changeNearLine, true);
	assert.equal(capitalisation.facts.authorReplied, true);
	assert.equal(capitalisation.facts.reviewerFollowedUp, false);
	assert.equal(capitalisation.facts.waiverWordsInFollowUp, false);
	assert.equal(keyOf(emptyState.label), "conversation-ask");
	assert.deepEqual(emptyState.at, { record: "general_comments.json", line: 0 });
	assert.equal(emptyState.facts?.issueNamedInReply, true);
	assert.equal(emptyState.facts.deferralWordsInReply, true);
	assert.equal(emptyState.facts.approvedAfterAsk, true);
	assert.match((result.directions ?? []).join(" "), /is a deferral to track, not a waiver/u);
});

void test("an ask cites the line of its body in the record that the server writes", async () => {
	const comments = [
		{ path: "App/A.swift", line: 3, body: "Rename this", author: "jennifer" },
		{ path: "App/A.swift", line: 3, body: "Done", author: "ada" },
		{ path: "App/B.swift", line: 9, body: "Split this", author: "jennifer" },
	];
	const result = await review("defers-review-asks-into-tracked-work", {
		item: { ...metadata, is_merged: true },
		inputs: atMerge,
		context: {
			"general_comments.json": { comments: [] },
			"comments.json": JSON.stringify(comments, null, 2),
		},
	});
	assert.deepEqual(
		result.leads.map((lead) => lead.at),
		[
			{ record: "comments.json", line: 5 },
			{ record: "comments.json", line: 17 },
		],
	);
});

void test("a requested review of unmerged work lists no ask, and says that the work is not merged", async () => {
	const context = {
		"comments.json": [{ path: "App/A.swift", line: 3, body: "Rename this", author: "jennifer" }],
		"general_comments.json": { comments: [] },
	};
	const open = await review("defers-review-asks-into-tracked-work", {
		item: { ...unmerged, state: "OPEN" },
		inputs: requestedOpen,
		context,
	});
	assert.deepEqual(open.leads, []);
	assert.match((open.directions ?? []).join(" "), /^The work is not merged\./u);
	const merged = await review("defers-review-asks-into-tracked-work", {
		item: { ...metadata, is_merged: true },
		inputs: requestedMerged,
		context,
	});
	assert.equal(merged.leads.length, 1);
});

void test("every captured linked issue is a row, and those with a checkable outcome are candidates the criteria decide", async () => {
	const context = {
		"linked_work_items.json": {
			workItems: [
				{
					number: 18,
					title: "Add ingredients",
					state: "CLOSED",
					body: "As a cook I want to add ingredients.",
				},
				{
					number: 12,
					title: "Persist alarms",
					state: "CLOSED",
					body: "## Tasks\n* [ ] Decide on persistence\n* [x] Store alarms on creation\n",
				},
				{
					number: 7,
					title: "Sub-issues",
					state: "OPEN",
					body: "",
					subIssuesTotal: 3,
					subIssuesCompleted: 1,
				},
				{ number: 8, title: "Total only", state: "OPEN", body: "", subIssuesTotal: 2 },
				{ number: 9, title: "None", state: "OPEN", body: "", subIssuesTotal: 0 },
				{
					number: 10,
					title: "Provider rollup",
					state: "OPEN",
					body: "",
					subIssuesTotal: 2,
					subIssuesCompleted: 1,
					subIssuesSource: "provider",
				},
				{
					number: 11,
					title: "Stored children, some open",
					state: "OPEN",
					body: "",
					subIssuesTotal: 3,
					subIssuesCompleted: 1,
					subIssuesSource: "children",
				},
				{
					number: 13,
					title: "Stored children, all closed",
					state: "CLOSED",
					body: "",
					subIssuesTotal: 2,
					subIssuesCompleted: 2,
					subIssuesSource: "children",
				},
				{
					number: 15,
					title: "Stored mirror has no children",
					state: "OPEN",
					body: "",
					subIssuesTotal: 0,
					subIssuesCompleted: 0,
					subIssuesSource: "children",
				},
				{
					number: 14,
					title: "Source not captured",
					state: "OPEN",
					body: "",
					subIssuesTotal: 2,
					subIssuesCompleted: 2,
				},
			],
		},
	};
	const result = await review("merge-confirms-the-linked-issue-outcome", {
		item: metadata,
		inputs: atMerge,
		context,
	});
	const keyOf = await leadKeys("merge-confirms-the-linked-issue-outcome");
	assert.equal(result.facts?.linkedItems, 10);
	assert.equal(result.facts.checkableItems, 7);
	assert.equal(result.facts.untickedItems, 1);
	const [
		story,
		tasks,
		subIssues,
		totalOnly,
		none,
		provider,
		someOpen,
		allClosed,
		emptyMirror,
		unrecognized,
	] = result.leads;
	assert.ok(
		story &&
			tasks &&
			subIssues &&
			totalOnly &&
			none &&
			provider &&
			someOpen &&
			allClosed &&
			emptyMirror &&
			unrecognized,
	);
	assert.deepEqual(story.at, { record: "linked_work_items/18.md" });
	assert.equal(keyOf(story.label), "no-task-list-or-outcome-heading");
	assert.equal(Object.hasOwn(story.facts ?? {}, "subIssuesTotal"), false);
	assert.equal(story.facts?.how, "named with a closing keyword in the body");
	assert.equal(keyOf(tasks.label), "checkable-outcome");
	assert.equal(tasks.facts?.ticked, 1);
	assert.equal(tasks.facts.unticked, 1);
	assert.equal(tasks.facts.how, "linked through a commit or another item");
	assert.equal(subIssues.facts?.subIssuesTotal, 3);
	assert.equal(subIssues.facts.subIssuesCompleted, 1);
	assert.equal(totalOnly.facts?.subIssuesTotal, 2);
	assert.equal(Object.hasOwn(totalOnly.facts ?? {}, "subIssuesCompleted"), false);
	assert.equal(keyOf(none.label), "no-checkable-outcome");
	assert.equal(none.facts?.subIssuesTotal, 0);
	for (const incomplete of [story, subIssues, totalOnly, none, unrecognized]) {
		assert.equal(Object.hasOwn(incomplete.facts ?? {}, "subIssuesSource"), false);
	}
	assert.equal(unrecognized.facts?.subIssuesCompleted, 2);
	assert.equal(provider.facts?.subIssuesSource, "provider");
	for (const stored of [someOpen, allClosed, emptyMirror]) {
		assert.equal(stored.facts?.subIssuesSource, "children");
	}
	assert.equal(keyOf(someOpen.label), "checkable-outcome");
	assert.equal(allClosed.facts?.subIssuesTotal, 2);
	assert.equal(keyOf(emptyMirror.label), "no-task-list-or-outcome-heading");
	assert.equal(emptyMirror.facts?.subIssuesTotal, 0);
	const said = (result.directions ?? []).join("\n");
	assert.match(result.directions?.[0] ?? "", /Each is a candidate: the criteria decide which/u);
	assert.doesNotMatch(said, /every one is an applicability/u);
	assert.match(
		said,
		/may omit other children, so all of them closed does not show that the whole hierarchy is complete/u,
	);
	const open = await review("merge-confirms-the-linked-issue-outcome", {
		item: { ...unmerged, state: "OPEN" },
		inputs: requestedOpen,
		context,
	});
	assert.deepEqual(open.leads, []);
	assert.match(open.directions?.[0] ?? "", /not merged/u);
	for (const source of ["provider", "children"]) {
		const zero = await review("merge-confirms-the-linked-issue-outcome", {
			item: metadata,
			inputs: atMerge,
			context: {
				"linked_work_items.json": {
					workItems: [
						{
							number: 1,
							title: "Empty count",
							body: "",
							subIssuesTotal: 0,
							subIssuesCompleted: 0,
							subIssuesSource: source,
						},
					],
				},
			},
		});
		assert.equal(zero.facts?.checkableItems, 0);
		assert.match(zero.directions?.[0] ?? "", /positive captured sub-issue count/u);
		assert.doesNotMatch(zero.directions?.[0] ?? "", /reported sub-issue rollup/u);
	}
});

const closingLink = {
	"linked_work_items.json": {
		workItems: [
			{
				number: 1,
				how: "closesOnMerge",
				title: "Add a short demo learner introduction",
				state: "OPEN",
				body: "## Acceptance criteria\n- [ ] The introduction names the learner\n",
			},
		],
	},
};
const openMergeRequest = {
	...unmerged,
	title: "#1: Add a short demo learner introduction",
	body: "Closes #1",
	source_branch: "1-demo-learner-introduction",
	state: "OPEN",
	is_merged: false,
};

void test("an open issue that an open merge request may close stays open, its closing link a candidate", async () => {
	const result = await review("links-the-change-to-its-issue", {
		item: openMergeRequest,
		context: closingLink,
	});
	const keyOf = await leadKeys("links-the-change-to-its-issue");
	const provider = result.leads[0];
	assert.ok(provider);
	assert.deepEqual(provider.at, { record: "linked_work_items.json" });
	assert.equal(keyOf(provider.label), "closing-reference");
	assert.equal(provider.facts?.number, 1);
	assert.equal(provider.facts.state, "OPEN");
	assert.equal(provider.facts.where, "linked_work_items.json (how: closesOnMerge)");
	assert.equal(result.facts?.providerClosingLinks, 1);
	assert.match(result.directions?.[1] ?? "", /1 linked issue\(s\) captured/u);
	const evidence = [
		...result.leads.map((lead) => JSON.stringify(lead)),
		...(result.directions ?? []),
	].join("\n");
	assert.doesNotMatch(evidence, /\bclosed\b/u);
	const unmergedOutcome = await review("merge-confirms-the-linked-issue-outcome", {
		item: openMergeRequest,
		inputs: requestedOpen,
		context: closingLink,
	});
	assert.deepEqual(unmergedOutcome.leads, []);
	assert.match(unmergedOutcome.directions?.[0] ?? "", /not merged/u);
});

void test("a merge request stored closed by its merge is merged, while its linked issue stays as last synced", async () => {
	const mergedRequest = { ...openMergeRequest, state: "CLOSED", is_merged: true };
	const result = await review("merge-confirms-the-linked-issue-outcome", {
		item: mergedRequest,
		inputs: atMerge,
		context: closingLink,
	});
	const keyOf = await leadKeys("merge-confirms-the-linked-issue-outcome");
	const [issue] = result.leads;
	assert.ok(issue);
	assert.equal(keyOf(issue.label), "checkable-outcome");
	assert.equal(issue.facts?.how, "a provider closing candidate");
	assert.equal(issue.facts.state, "OPEN");
	assert.match(
		result.directions?.[0] ?? "",
		/1 captured linked issue\(s\) state a checkable outcome/u,
	);
	assert.deepEqual(
		await review("merge-confirms-the-linked-issue-outcome", {
			item: mergedRequest,
			inputs: requestedMerged,
			context: closingLink,
		}),
		result,
	);
});

void test("an uncaptured linked-item file is a collection gap, apart from a capture that holds no issue", async () => {
	const merged = { ...openMergeRequest, state: "CLOSED", is_merged: true };
	const uncaptured = await review("merge-confirms-the-linked-issue-outcome", {
		item: merged,
		inputs: atMerge,
		context: {},
	});
	const capturedEmpty = await review("merge-confirms-the-linked-issue-outcome", {
		item: merged,
		inputs: atMerge,
		context: { "linked_work_items.json": { workItems: [] } },
	});
	assert.match(uncaptured.directions?.[0] ?? "", /was not captured: a collection gap/u);
	assert.match(capturedEmpty.directions?.[0] ?? "", /^The capture holds no linked issue/u);
});

void test("every authored commit is one lead, however many there are", async () => {
	const commits = Array.from({ length: 12 }, (_, i) => ({
		sha: `${String(i).padStart(7, "0")}abcd`,
		message: i === 3 ? "fix" : `Add step ${String(i)} and wire it`,
		authoredAt: `2026-04-12T1${String(i % 10)}:00:00Z`,
		files: [
			{ status: "M", path: `App/Step${String(i)}.swift` },
			{ status: "A", path: "README.md" },
		],
	}));
	for (const slug of ["commit-subjects-explain-each-change", "commits-are-atomic-and-cohesive"]) {
		const result = await review(slug, {
			item: metadata,
			context: { "commits.json": commitsRecord(commits) },
		});
		const keyOf = await leadKeys(slug);
		assert.equal(result.leads.length, 12);
		assert.equal(result.directions?.length, 2);
		const [first] = result.leads;
		const bare = result.leads[3];
		assert.ok(first && bare);
		assert.deepEqual(first.at, { record: "commits.json" });
		assert.equal(keyOf(first.label), "commit");
		assert.deepEqual(
			first.facts,
			slug === "commit-subjects-explain-each-change"
				? { sha: "0000000", bare: false, repeat: false, cutOff: false }
				: {
						sha: "0000000",
						bare: false,
						repeat: false,
						joinedClauses: true,
						cutOff: false,
						bodyLines: 0,
						files: 2,
						moved: 0,
						paths: "App/Step0.swift, README.md",
					},
		);
		assert.equal(bare.facts?.bare, true);
	}
});

async function linkedReview(body: string, workItems?: unknown[]) {
	return review("links-the-change-to-its-issue", {
		item: { ...metadata, title: "Add the view", source_branch: "add-the-view", body },
		context: workItems === undefined ? {} : { "linked_work_items.json": { workItems } },
	});
}

void test("authored references never borrow a same-number target from another repository or provider", async () => {
	const foreign = "https://gitlab.example/group/sub/other/-/issues/7";
	const result = await linkedReview(
		"Refs #7 and group/sub/other#7; also owner/repo#7 and other#7.",
		[
			{
				number: 7,
				title: "Foreign issue",
				body: "Rename the app",
				state: "CLOSED",
				how: "closesOnMerge",
				url: foreign,
			},
		],
	);
	const captured = result.leads.filter(
		(lead) => "record" in lead.at && lead.at.record === "linked_work_items.json",
	);
	assert.equal(captured.length, 1);
	const target = captured[0]?.facts;
	assert.ok(target);
	assert.equal(target.target, foreign);
	assert.equal(target.title, "Foreign issue");
	const authored = result.leads.filter(
		(lead) => !("record" in lead.at && lead.at.record === "linked_work_items.json"),
	);
	assert.deepEqual(
		authored.map((lead) => lead.facts?.target),
		["team/project", "group/sub/other", "owner/repo", "other"],
	);
	for (const lead of authored) {
		assert.ok(lead.facts);
		assert.equal(lead.facts.number, 7);
		assert.equal(lead.facts.title, undefined);
		assert.equal(lead.facts.state, undefined);
		assert.equal(lead.facts.inLinkedItems, undefined);
	}
});

void test("captured closing candidates and mentions keep their own target, state and title", async () => {
	const result = await linkedReview("Closes #18; see #21 and #99.", [
		{
			number: 18,
			title: "Add ingredients",
			body: "Add a list",
			state: "OPEN",
			how: "closesOnMerge",
			url: "https://example.org/team/project/issues/18",
		},
		{
			number: 21,
			title: "Confetti",
			body: "Add confetti",
			state: "CLOSED",
			how: "mentions",
			url: "https://example.org/team/other/issues/21",
		},
	]);
	const keyOf = await leadKeys("links-the-change-to-its-issue");
	const captured = result.leads.filter(
		(lead) => "record" in lead.at && lead.at.record === "linked_work_items.json",
	);
	assert.deepEqual(
		captured.map((lead) => {
			assert.ok(lead.facts);
			return [keyOf(lead.label), lead.facts.title, lead.facts.state];
		}),
		[
			["closing-reference", "Add ingredients", "OPEN"],
			["linked-issue", "Confetti", "CLOSED"],
		],
	);
	const unresolved = result.leads.find((lead) => lead.facts?.number === 99);
	assert.ok(unresolved?.facts);
	assert.ok(result.facts);
	assert.equal(unresolved.facts.title, undefined);
	assert.equal(result.facts.linkedWorkItems, 2);
	assert.equal(result.facts.providerClosingLinks, 1);
});

void test("missing and empty linked captures preserve the collection boundary without inventing repository counts", async () => {
	for (const workItems of [undefined, []]) {
		const result = await linkedReview("Adds the view.", workItems);
		assert.deepEqual(result.leads, []);
		assert.ok(result.facts);
		assert.equal(result.facts.referencesFound, 0);
		assert.equal(result.facts.linkedWorkItems, workItems === undefined ? -1 : 0);
		assert.match(result.directions?.join("\n") ?? "", /Read commits.json/u);
		assert.match(
			result.directions?.join("\n") ?? "",
			workItems === undefined ? /collection gap/u : /partial capture cannot prove/u,
		);
	}
});

void test("a captured issue without a URL remains a target with unknown location", async () => {
	const result = await linkedReview("Refs #7", [
		{ number: 7, title: "Captured issue", body: "", how: "closesOnMerge" },
	]);
	const target = result.leads[0]?.facts;
	assert.ok(target);
	assert.equal(target.target, "unknown");
	assert.equal(target.title, "Captured issue");
	assert.equal(result.leads[1]?.facts?.title, undefined);
});

void test("every reviewer comment is one lead with the reply, the thread and the later commits beside it", async () => {
	const result = await review("engaging-with-inline-review-comments", {
		item: metadata,
		change: new Map([diffFile("App/AddIngredientView.swift", [54, 56])]),
		context: {
			"comments.json": [
				{
					id: 1,
					thread: 10,
					path: "App/AddIngredientView.swift",
					line: 55,
					side: "RIGHT",
					body: "Maybe a visual indication when the stock value is wrong?",
					author: "jennifer",
					created_at: "2026-04-13T14:18:20Z",
				},
				{
					id: 2,
					thread: 10,
					in_reply_to: 1,
					path: "App/AddIngredientView.swift",
					line: 55,
					body: "Good idea, done in the next commit — it now turns the field red.",
					author: "ada",
					created_at: "2026-04-13T14:40:00Z",
				},
				{
					id: 3,
					thread: 11,
					path: "App/Model.swift",
					line: 9,
					outdated: true,
					body: "Rename this",
					author: "project_42_bot_3f9a",
					bot: true,
					created_at: "2026-04-13T14:19:00Z",
				},
			],
			"review_threads.json": {
				threads: [
					{
						id: 10,
						path: "App/AddIngredientView.swift",
						line: 55,
						state: "RESOLVED",
						resolvedBy: "ada",
					},
					{ id: 11, path: "App/Model.swift", line: 9, state: "UNRESOLVED" },
				],
				reviewDecisions: [],
			},
			"commits.json": commitsRecord([
				{
					sha: "aaaaaaa1111",
					message: "Add the view",
					authoredAt: "2026-04-13T10:00:00Z",
					files: [{ status: "A", path: "App/AddIngredientView.swift" }],
				},
				{
					sha: "bbbbbbb2222",
					message: "Turn the field red",
					authoredAt: "2026-04-13T14:50:00Z",
					files: [{ status: "M", path: "App/AddIngredientView.swift" }],
				},
				{
					sha: "ccccccc3333",
					message: "Tidy",
					authoredAt: "2026-04-13T15:00:00Z",
					files: [{ status: "M", path: "App/Other.swift" }],
				},
			]),
		},
	});
	const keyOf = await leadKeys("engaging-with-inline-review-comments");
	assert.equal(result.leads.length, 2);
	const [stock, rename] = result.leads;
	assert.ok(stock && rename);
	assert.equal(keyOf(stock.label), "reviewer-comment");
	assert.deepEqual(stock.at, { record: "comments.json", line: 0 });
	assert.deepEqual(stock.facts, {
		by: "jennifer",
		bot: false,
		at: "2026-04-13T14:18:20Z",
		path: "App/AddIngredientView.swift",
		side: "RIGHT",
		outdated: false,
		authorReplied: true,
		replyExcerpt: "Good idea, done in the next commit — it now turns the field red.",
		threadResolved: true,
		resolvedBy: "ada",
		afterHandOff: false,
		fileInChange: true,
		changeNearLine: true,
		commitsAfter: 2,
		commitsAfterTouchingFile: 1,
		line: 55,
	});
	assert.equal(rename.facts?.bot, true);
	assert.equal(rename.facts.outdated, true);
	assert.equal(rename.facts.authorReplied, false);
	assert.equal(rename.facts.threadResolved, false);
	assert.equal(result.facts?.reviewerComments, 1);
	assert.equal(result.facts.botComments, 1);
	assert.match(
		(result.directions ?? []).join(" "),
		/^1 reviewer note\(s\) \(1 inline, 0 conversation\), 1 more by bots; 1 have a later author note\. 1 have a later authored commit touching the commented file; 0 were posted after the work merged or closed\./u,
	);
});

void test("a conversation note is a lead, with the reviewer's approval, their settling note and the hand-off beside it", async () => {
	const result = await review("engaging-with-inline-review-comments", {
		item: metadata,
		change: new Map([diffFile("App/QuizView.swift", [3])]),
		context: {
			"comments.json": [
				{
					id: 1,
					thread: 10,
					path: "App/QuizView.swift",
					line: 3,
					body: "Nitpick: the header still says Draft",
					author: "tutor",
					created_at: "2026-04-13T12:00:00Z",
				},
			],
			"general_comments.json": {
				comments: [
					{
						author: "tutor",
						body: "Approved, it works. Two ideas for next iteration: move the date format into a setting.",
						createdAt: "2026-04-13T13:00:00.100Z",
					},
					{
						author: "tutor",
						body: "Perfect! Thank you for incorporating the changes",
						createdAt: "2026-04-13T13:05:00Z",
					},
					{
						author: "peer",
						body: "The composition needs to be flipped.",
						createdAt: "2026-04-13T16:00:00Z",
					},
				],
			},
			"review_threads.json": {
				threads: [
					{ id: 10, path: "App/QuizView.swift", line: 3, state: "RESOLVED", resolvedBy: "tutor" },
				],
				reviewDecisions: [
					{ state: "APPROVED", author: "tutor", submittedAt: "2026-04-13T13:00:00.250Z" },
				],
			},
			"commits.json": commitsRecord([
				{
					sha: "aaaaaaa1111",
					message: "update the quiz view",
					authoredAt: "2026-04-13T12:30:00Z",
					files: [{ status: "M", path: "App/QuizView.swift" }],
				},
			]),
		},
	});
	const keyOf = await leadKeys("engaging-with-inline-review-comments");
	const [nitpick, advice, thanks, afterMerge] = result.leads;
	assert.ok(nitpick && advice && thanks && afterMerge);
	assert.match(String(nitpick.facts?.reviewerLaterNote), /^Approved, it works\./u);
	assert.equal(
		nitpick.facts?.reviewerApprovedAfter,
		"3600s later; 1 commit(s) and 0 author note(s) between",
	);
	assert.equal(keyOf(advice.label), "conversation-comment");
	assert.deepEqual(advice.at, { record: "general_comments.json", line: 0 });
	assert.equal(
		advice.facts?.reviewerApprovedAfter,
		"0s later; 0 commit(s) and 0 author note(s) between",
	);
	assert.equal(advice.facts.afterHandOff, false);
	assert.equal(advice.facts.authorNotesAfter, 0);
	assert.equal(afterMerge.facts?.by, "peer");
	assert.equal(afterMerge.facts.afterHandOff, true);
	assert.equal("reviewerApprovedAfter" in afterMerge.facts, false);
	assert.equal(result.facts?.conversationComments, 3);
	const directions = (result.directions ?? []).join(" ");
	assert.match(directions, /1 were posted after the work merged or closed/u);
	assert.doesNotMatch(directions, /who clicked it|applicability did not arise/u);
});

void test("a comments file that was not captured is told apart from one with no comment by others", async () => {
	const noFile = await review("defers-review-asks-into-tracked-work", {
		item: metadata,
		inputs: atMerge,
		context: {},
	});
	assert.match(noFile.directions?.[0] ?? "", /collection gap and record no problem/u);
	assert.equal(noFile.facts?.commentsFileAbsent, 1);
	const noOthers = await review("defers-review-asks-into-tracked-work", {
		item: metadata,
		inputs: atMerge,
		context: {
			"comments.json": [],
			"general_comments.json": {
				comments: [{ author: "ada", body: "Ready", createdAt: "2026-04-13T14:00:00Z" }],
			},
		},
	});
	assert.match(
		noOthers.directions?.[0] ?? "",
		/^No comment by anyone other than the author in the captured record \(0 inline comment\(s\), 1 conversation comment\(s\)\)/u,
	);
	assert.equal(noOthers.facts?.commentsFileAbsent, 0);
	const inlineNoFile = await review("engaging-with-inline-review-comments", {
		item: metadata,
		inputs: atMerge,
		context: {},
	});
	assert.match(inlineNoFile.directions?.[0] ?? "", /^No comments file was captured/u);
	const inlineNoOthers = await review("engaging-with-inline-review-comments", {
		item: metadata,
		inputs: atMerge,
		context: { "comments.json": [] },
	});
	assert.match(
		inlineNoOthers.directions?.[0] ?? "",
		/^No note by anyone other than the author in the captured record \(0 inline comment\(s\), general_comments\.json not captured\)\.$/u,
	);
});

void test("one missing comments surface cannot prove no deferred ask", async () => {
	for (const context of [{ "comments.json": [] }, { "general_comments.json": { comments: [] } }]) {
		const result = await review("defers-review-asks-into-tracked-work", {
			item: metadata,
			inputs: atMerge,
			context,
		});
		const said = (result.directions ?? []).join("\n");
		assert.match(said, /collection gap and record no problem/u);
		assert.doesNotMatch(said, /applicability did not arise/u);
	}
});

void test("current unresolved threads do not establish their state at an earlier merge", async () => {
	const result = await review("merged-past-unresolved-review-threads", {
		item: metadata,
		inputs: atMerge,
		context: {
			"review_threads.json": {
				threads: [
					{ id: 1, state: "UNRESOLVED", createdAt: "2026-04-13T16:00:00Z" },
					{ id: 2, state: "UNRESOLVED" },
				],
				reviewDecisions: [],
			},
		},
	});
	assert.equal(result.leads[1]?.facts?.createdAt, "2026-04-13T16:00:00Z");
	assert.equal(Object.hasOwn(result.leads[2]?.facts ?? {}, "createdAt"), false);
	assert.match((result.directions ?? []).join("\n"), /not proven open at merge/u);
	assert.match(
		(result.directions ?? []).join("\n"),
		/where the record cannot settle that order, name the missing date and decide as the criteria say/u,
	);
});

void test("a resolution the record does not date by the merge, or a merge it does not date, stays a historical question", async () => {
	const context = {
		"review_threads.json": {
			threads: [
				{
					id: 20,
					state: "RESOLVED",
					createdAt: "2026-04-13T14:00:00Z",
					resolvedAt: "2026-04-13T14:30:00Z",
				},
				{
					id: 21,
					state: "RESOLVED",
					createdAt: "2026-04-13T14:00:00Z",
					resolvedAt: "2026-04-13T15:30:00Z",
				},
				{ id: 22, state: "RESOLVED", createdAt: "2026-04-13T14:00:00Z" },
				{ id: 23, state: "RESOLVED", createdAt: "2026-04-13T14:00:00Z", resolvedAt: "yesterday" },
				{ id: 24, state: "RESOLVED", createdAt: "2026-04-13T16:00:00Z" },
			],
			reviewDecisions: [],
		},
	};
	const result = await review("merged-past-unresolved-review-threads", {
		item: metadata,
		inputs: atMerge,
		context,
	});
	const keyOf = await leadKeys("merged-past-unresolved-review-threads");
	assert.deepEqual(
		result.leads
			.filter((lead) => keyOf(lead.label) !== "merge")
			.map((lead) => [
				lead.facts?.id,
				keyOf(lead.label),
				lead.facts?.createdAt,
				lead.facts?.resolvedAt,
			]),
		[
			[21, "thread-resolved-after-the-merge", "2026-04-13T14:00:00Z", "2026-04-13T15:30:00Z"],
			[22, "resolved-thread-resolution-time-unknown", "2026-04-13T14:00:00Z", undefined],
			[23, "resolved-thread-resolution-time-unknown", "2026-04-13T14:00:00Z", "yesterday"],
		],
	);
	assert.equal(result.facts?.candidateThreads, 3);
	assert.equal(result.facts.resolutionTimeUnknown, 2);
	assert.equal(result.facts.resolvedBeforeMerge, 1);

	const [missing, invalid, open] = await Promise.all([
		review("merged-past-unresolved-review-threads", {
			item: unmerged,
			inputs: atMerge,
			context,
		}),
		review("merged-past-unresolved-review-threads", {
			item: { ...metadata, merged_at: "not a time" },
			inputs: atMerge,
			context,
		}),
		review("merged-past-unresolved-review-threads", {
			item: { ...unmerged, state: "OPEN" },
			inputs: requestedOpen,
			context,
		}),
	]);
	for (const undated of [missing, invalid]) {
		assert.deepEqual(
			undated.leads
				.filter((lead) => keyOf(lead.label) !== "merge")
				.map((lead) => [lead.facts?.id, keyOf(lead.label)]),
			[
				[20, "resolved-thread-merge-time-unknown"],
				[21, "resolved-thread-merge-time-unknown"],
				[22, "resolved-thread-both-times-unknown"],
				[23, "resolved-thread-both-times-unknown"],
				[24, "resolved-thread-both-times-unknown"],
			],
		);
		assert.equal(undated.facts?.resolutionTimeUnknown, 3);
		assert.equal(undated.facts.resolvedBeforeMerge, undefined);
	}
	assert.deepEqual(open.leads, []);
	assert.match(open.directions?.[0] ?? "", /not merged/u);
});

void test("the merge practices read the threads and decisions as rows against the merge", async () => {
	const record = {
		"review_threads.json": {
			review_decision: "APPROVED",
			threads: [
				{
					id: 10,
					path: "App/View.swift",
					line: 55,
					state: "RESOLVED",
					resolvedBy: "ada",
					createdAt: "2026-04-13T14:18:20Z",
					resolvedAt: "2026-04-13T14:30:00Z",
				},
				{
					id: 11,
					path: "App/Model.swift",
					line: 9,
					state: "UNRESOLVED",
					outdated: true,
					createdAt: "2026-04-13T14:19:00Z",
				},
				{
					id: 12,
					path: "App/Late.swift",
					line: 3,
					state: "RESOLVED",
					resolvedBy: "ada",
					createdAt: "2026-04-13T14:20:00Z",
					resolvedAt: "2026-04-13T16:00:00Z",
				},
			],
			reviewDecisions: [
				{
					state: "CHANGES_REQUESTED",
					author: "jennifer",
					submittedAt: "2026-04-13T14:20:00Z",
					body: "Please fix the stock check",
				},
				{ state: "APPROVED", author: "ada", submittedAt: "2026-04-13T14:30:00Z" },
				{ state: "APPROVED", author: "jennifer", submittedAt: "2026-04-13T15:00:00Z" },
				{
					state: "APPROVED",
					author: "heph_introcourse_tutor_e2e",
					bot: true,
					submittedAt: "2026-04-13T15:02:00Z",
				},
				{ state: "APPROVED", author: "tom", submittedAt: "2026-04-13T16:00:00Z" },
			],
		},
	};
	const merged = { ...metadata, is_merged: true, merged_by: "ada" };
	const unresolved = await review("merged-past-unresolved-review-threads", {
		item: merged,
		inputs: atMerge,
		context: record,
	});
	const threadKey = await leadKeys("merged-past-unresolved-review-threads");
	assert.equal(unresolved.facts?.candidateThreads, 2);
	assert.equal(unresolved.facts.resolvedBeforeMerge, 1);
	assert.equal(unresolved.facts.mergedByIsAuthor, 1);
	assert.equal(unresolved.facts.threadsFileAbsent, 0);
	const [merge, thread] = unresolved.leads;
	assert.ok(merge && thread);
	assert.equal(threadKey(merge.label), "merge");
	assert.deepEqual(merge.at, { record: "metadata.json" });
	assert.equal(merge.facts?.mergedBy, "ada");
	assert.equal(merge.facts.mergedByIsAuthor, true);
	assert.equal(merge.facts.reviewDecision, undefined);
	assert.equal(threadKey(thread.label), "unresolved-thread");
	assert.deepEqual(thread.at, { record: "review_threads.json" });
	assert.deepEqual(thread.facts, {
		id: 11,
		state: "UNRESOLVED",
		createdAt: "2026-04-13T14:19:00Z",
		path: "App/Model.swift",
		line: 9,
		outdated: true,
	});
	const late = unresolved.leads[2];
	assert.ok(late);
	assert.equal(threadKey(late.label), "thread-resolved-after-the-merge");
	assert.equal(late.facts?.resolvedAt, "2026-04-13T16:00:00Z");
	assert.match(
		unresolved.directions?.[0] ?? "",
		/^Merged by ada \(the author\); 3 thread\(s\) captured, 2 candidate thread\(s\)/u,
	);

	const decisions = await review("merges-only-after-approval", {
		item: merged,
		inputs: atMerge,
		context: record,
	});
	const aggregate = decisions.leads.find((lead) => lead.facts?.reviewDecision === "APPROVED");
	assert.ok(aggregate);
	assert.deepEqual(aggregate.at, { record: "review_threads.json" });
	const decisionKey = await leadKeys("merges-only-after-approval");
	assert.equal(decisions.facts?.decisions, 5);
	assert.equal(decisions.facts.approvalsBeforeMergeByOthers, 1);
	const rows = decisions.leads.filter((lead) => decisionKey(lead.label) === "review-decision");
	assert.deepEqual(
		rows.map((lead) => [
			lead.facts?.author,
			lead.facts?.state,
			lead.facts?.beforeMerge,
			lead.facts?.isAuthor,
		]),
		[
			["jennifer", "CHANGES_REQUESTED", true, false],
			["ada", "APPROVED", true, true],
			["jennifer", "APPROVED", true, false],
			["heph_introcourse_tutor_e2e", "APPROVED", true, false],
			["tom", "APPROVED", false, false],
		],
	);
	assert.equal(rows[3]?.facts?.bot, true);
	assert.deepEqual(rows[0]?.at, { record: "review_threads.json" });
	const last = decisions.leads.filter(
		(lead) => decisionKey(lead.label) === "last-decision-by-reviewer",
	);
	assert.deepEqual(
		last.map((lead) => [lead.facts?.author, lead.facts?.state]),
		[
			["jennifer", "APPROVED"],
			["ada", "APPROVED"],
			["heph_introcourse_tutor_e2e", "APPROVED"],
			["tom", "APPROVED"],
		],
	);

	const absent = await review("merges-only-after-approval", {
		item: merged,
		inputs: atMerge,
		context: {},
	});
	assert.equal(absent.facts?.threadsFileAbsent, 1);
	assert.match(absent.directions?.[0] ?? "", /^No review threads file was captured/u);
	const open = await review("merges-only-after-approval", {
		item: { ...metadata, state: "OPEN", merged_at: undefined },
		inputs: requestedOpen,
		context: {},
	});
	assert.deepEqual(open.leads, []);
	assert.match(open.directions?.[0] ?? "", /not merged/u);
});

void test("an approval by an account marked bot is kept as a row but counted as no person's approval", async () => {
	const merged = { ...metadata, is_merged: true };
	const tutorBot = { author: "heph_introcourse_tutor_e2e", bot: true };
	const cases = [
		{
			name: "bot only",
			decisions: [{ ...tutorBot, state: "APPROVED", submittedAt: "2026-04-13T15:00:00Z" }],
			approvals: 0,
		},
		{
			name: "a person's request for changes, then a bot's approval",
			decisions: [
				{ state: "CHANGES_REQUESTED", author: "jennifer", submittedAt: "2026-04-13T14:20:00Z" },
				{ ...tutorBot, state: "APPROVED", submittedAt: "2026-04-13T15:00:00Z" },
			],
			approvals: 0,
		},
		{
			name: "a person's approval, then a bot's request for changes",
			decisions: [
				{ state: "APPROVED", author: "jennifer", submittedAt: "2026-04-13T14:30:00Z" },
				{ ...tutorBot, state: "CHANGES_REQUESTED", submittedAt: "2026-04-13T15:00:00Z" },
			],
			approvals: 1,
		},
	];
	const keyOf = await leadKeys("merges-only-after-approval");
	for (const { name, decisions, approvals } of cases) {
		const result = await review("merges-only-after-approval", {
			item: merged,
			inputs: atMerge,
			context: { "review_threads.json": { threads: [], reviewDecisions: decisions } },
		});
		assert.equal(result.facts?.approvalsBeforeMergeByOthers, approvals, name);
		assert.equal(result.facts.decisionsByBots, 1, name);
		const botLeads = result.leads.filter((lead) => lead.facts?.bot === true);
		assert.deepEqual(
			botLeads.map((lead) => keyOf(lead.label)),
			["review-decision", "last-decision-by-reviewer"],
			name,
		);
		assert.match(
			result.directions?.[0] ?? "",
			new RegExp(
				`, ${approvals} of them an undismissed APPROVED before the merge by an account other than the author's that is not marked bot\\.`,
				"u",
			),
			name,
		);
	}
});

void test("unknown approval times establish neither before-merge absence nor a last historical decision", async () => {
	const result = await review("merges-only-after-approval", {
		item: { ...metadata, is_merged: true },
		inputs: atMerge,
		context: {
			"review_threads.json": {
				threads: [],
				reviewDecisions: [
					{ state: "CHANGES_REQUESTED", author: "jennifer", submittedAt: "2026-04-13T14:20:00Z" },
					{ state: "APPROVED", author: "jennifer" },
					{ state: "APPROVED", author: "tom", submittedAt: "2026-04-13T15:00:00Z" },
				],
			},
		},
	});
	const keyOf = await leadKeys("merges-only-after-approval");
	const unknown = result.leads.find(
		(lead) =>
			keyOf(lead.label) === "review-decision" &&
			lead.facts?.author === "jennifer" &&
			lead.facts.state === "APPROVED",
	);
	assert.ok(unknown);
	assert.equal(Object.hasOwn(unknown.facts ?? {}, "beforeMerge"), false);
	assert.equal(result.facts?.unknownDecisionTimes, 1);
	assert.equal(result.facts.approvalsBeforeMergeByOthers, 1);
	assert.deepEqual(
		result.leads
			.filter((lead) => keyOf(lead.label) === "last-decision-by-reviewer")
			.map((lead) => lead.facts?.author),
		["tom"],
	);
	assert.match(result.directions?.[2] ?? "", /lower bound.*no historical last decision/u);
});

void test("review engagement distinguishes unavailable commit evidence from a captured empty range", async () => {
	for (const captured of [false, true]) {
		const result = await review("engaging-with-inline-review-comments", {
			item: metadata,
			context: {
				"comments.json": [
					{
						author: "reviewer",
						body: "Please fix retry.",
						path: "App.swift",
						line: 4,
						created_at: "2026-04-13T14:00:00Z",
					},
				],
				"review_threads.json": {
					threads: [],
					reviewDecisions: [
						{ state: "APPROVED", author: "reviewer", submittedAt: "2026-04-13T14:30:00Z" },
					],
				},
				...(captured ? { "commits.json": commitsRecord([]) } : {}),
			},
		});
		const note = result.leads[0];
		assert.ok(note);
		const said = (result.directions ?? []).join(" ");
		assert.equal(result.facts?.commitsFileAbsent, captured ? 0 : 1);
		assert.equal(note.facts?.commitsAfterTouchingFile, captured ? 0 : "unknown");
		assert.equal(result.facts.withLaterCommitOnFile, captured ? 0 : undefined);
		assert.match(
			said,
			captured
				? /\. 0 have a later authored commit touching the commented file;/u
				: /How many have a later authored commit touching the commented file is unknown/u,
		);
		assert.match(
			String(note.facts.reviewerApprovedAfter),
			captured ? /0 commit\(s\)/u : /unknown commit count/u,
		);
		assert.match(said, captured ? /0 captured commit\(s\)/u : /Commit capture: unavailable/u);
	}
});
