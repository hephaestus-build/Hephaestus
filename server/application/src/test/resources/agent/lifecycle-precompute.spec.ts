import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { isPracticeModule } from "../../../../../../docker/agents/precompute/lib/practice-contract.ts";
import type { DiffFile } from "../../../../../../docker/agents/precompute/lib/types.ts";

const repositoryRoot = fileURLToPath(new URL("../../../../../../", import.meta.url));

/**
 * Stages one lifecycle script beside a `lib/` link, as the runner does, with the context files the
 * server projects and the change view the container derives.
 */
async function stage(
	slug: string,
	context: Record<string, unknown>,
	commits: {
		sha: string;
		message: string;
		authoredAt: string;
		files?: { status: string; path: string }[];
	}[] = [],
) {
	const root = mkdtempSync(nodePath.join(tmpdir(), "lifecycle-precompute-"));
	mkdirSync(nodePath.join(root, "practices"));
	mkdirSync(nodePath.join(root, "context"));
	mkdirSync(nodePath.join(root, "work/change"), { recursive: true });
	writeFileSync(nodePath.join(root, "package.json"), '{"type":"module"}\n');
	symlinkSync(
		nodePath.join(repositoryRoot, "docker/agents/precompute/lib"),
		nodePath.join(root, "lib"),
	);
	for (const [name, value] of Object.entries(context)) {
		writeFileSync(nodePath.join(root, "context", name), JSON.stringify(value));
	}
	writeFileSync(
		nodePath.join(root, "context/commits.json"),
		JSON.stringify(
			{
				commits: commits.map((c) => ({
					files: [],
					...c,
					author: "ada",
					committer: "ada",
					committedAt: c.authoredAt,
					parents: [],
				})),
			},
			null,
			2,
		),
	);
	const staged = nodePath.join(root, "practices", `${slug}.ts`);
	cpSync(
		nodePath.join(
			repositoryRoot,
			"server/application/src/main/resources/practices/precompute",
			`${slug}.ts`,
		),
		staged,
	);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) {
		throw new Error("script does not export a default function");
	}
	return {
		root,
		script: mod.default,
		contextDir: nodePath.join(root, "context"),
		changeDir: nodePath.join(root, "work/change"),
	};
}

const metadata = {
	pr_number: 16,
	pr_url: "https://example.org/team/project/pull/16",
	repository_full_name: "team/project",
	source_branch: "18-add-ingredient-view",
	target_branch: "main",
	commit_sha: "abc123",
	title: "#18: Add ingredient creation view",
	body: "Closes #18\n\nAdds the view.",
	state: "MERGED",
	author: "ada",
	merged_by: "ada",
	created_at: "2026-04-13T10:16:13Z",
	merged_at: "2026-04-13T15:06:54Z",
};

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

void test("plans-first puts the issue's opening beside the earliest commit, never beside the pull request", async () => {
	const { root, script, contextDir, changeDir } = await stage(
		"plans-the-work-in-an-issue-first",
		{
			"linked_work_items.json": {
				workItems: [
					{ number: 18, title: "Add ingredients", createdAt: "2026-04-13T09:35:24Z", body: "" },
					{ number: 12, title: "Unrelated", createdAt: "2026-04-01T00:00:00Z", body: "" },
				],
			},
		},
		[
			{ sha: "f46a897aaaa", message: "Add AddIngredientView", authoredAt: "2026-04-12T17:11:59Z" },
			{ sha: "dd6e2f5bbbb", message: "Wire it", authoredAt: "2026-04-12T17:38:00Z" },
		],
	);
	try {
		const result = await script(
			nodePath.join(root, "repo"),
			new Map(),
			metadata,
			contextDir,
			changeDir,
		);
		assert.equal(result.hints.length, 1);
		const [hint] = result.hints;
		assert.ok(hint);
		assert.equal(hint.flags.number, 18);
		assert.equal(hint.flags.firstCommit, "f46a897");
		assert.equal(hint.flags.hoursFromIssueToFirstCommit, "-16.4");
		assert.match(
			hint.context,
			/the issue was opened 16\.4 h after the earliest commit was authored/u,
		);
		assert.equal(result.metrics.issueOpenedAfterFirstCommitMinutes, 983);
		assert.match(result.directions[0] ?? "", /never against the pull request's creation/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("deferred asks are one row each, with the record's facts and none of the judgement", async () => {
	const { root, script, contextDir, changeDir } = await stage(
		"defers-review-asks-into-tracked-work",
		{
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
						body: "Please add the confetti in your next MR",
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
		},
	);
	try {
		const result = await script(
			nodePath.join(root, "repo"),
			new Map([diffFile("App/AddIngredientView.swift", [12, 13])]),
			metadata,
			contextDir,
			changeDir,
		);
		assert.equal(result.metrics.asks, 3);
		assert.equal(result.metrics.inlineAsks, 2);
		assert.equal(result.metrics.conversationAsks, 1);
		const [stock, capitalisation, confetti] = result.hints;
		assert.ok(stock && capitalisation && confetti);
		// The stock ask: the file is in the change but nothing near line 55 changed, no reply,
		// resolved — and the reviewer's own later word on the thread drops it.
		assert.equal(stock.flags.fileInChange, true);
		assert.equal(stock.flags.changeNearLine, false);
		assert.equal(stock.flags.authorReplied, false);
		assert.equal(stock.flags.threadResolved, true);
		assert.equal(stock.flags.reviewerFollowedUp, true);
		assert.equal(stock.flags.waiverWordsInFollowUp, true);
		assert.equal(stock.flags.approvedAfterAsk, true);
		// The capitalisation ask: changed beside it, and the author answered; nobody else did.
		assert.equal(capitalisation.flags.changeNearLine, true);
		assert.equal(capitalisation.flags.authorReplied, true);
		assert.equal(capitalisation.flags.reviewerFollowedUp, false);
		assert.equal(capitalisation.flags.waiverWordsInFollowUp, false);
		// The conversation ask: the reply names an issue and defers in words, and the approval
		// followed it — a fact the review weighs, not a waiver by itself.
		assert.equal(confetti.pattern, "conversation ask");
		assert.equal(confetti.flags.issueNamedInReply, true);
		assert.equal(confetti.flags.deferralWordsInReply, true);
		assert.equal(confetti.flags.approvedAfterAsk, true);
		assert.match(result.directions[0] ?? "", /is a deferral to track, not a waiver/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("every linked issue with a checkable outcome is an occasion, not only the one the body closes", async () => {
	const { root, script, contextDir, changeDir } = await stage(
		"merge-confirms-the-linked-issue-outcome",
		{
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
				],
			},
		},
	);
	try {
		const result = await script(
			nodePath.join(root, "repo"),
			new Map(),
			metadata,
			contextDir,
			changeDir,
		);
		assert.equal(result.metrics.linkedItems, 3);
		assert.equal(result.metrics.checkableItems, 2);
		assert.equal(result.metrics.untickedItems, 1);
		const [story, tasks, subIssues] = result.hints;
		assert.ok(story && tasks && subIssues);
		assert.equal(story.pattern, "no checkable outcome");
		assert.equal(story.flags.how, "closed by the body");
		assert.equal(tasks.pattern, "checkable outcome");
		assert.equal(tasks.flags.ticked, 1);
		assert.equal(tasks.flags.unticked, 1);
		assert.equal(tasks.flags.how, "linked through a commit or another item");
		assert.equal(subIssues.flags.subIssuesTotal, 3);
		assert.match(result.directions[0] ?? "", /every one is an occasion/u);
		// An unmerged change has no occasion, whatever the items say.
		const open = await script(
			nodePath.join(root, "repo"),
			new Map(),
			{ ...metadata, state: "OPEN" },
			contextDir,
			changeDir,
		);
		assert.match(open.directions[0] ?? "", /not merged/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("every authored commit is one record row, however many there are", async () => {
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
		const { root, script, contextDir, changeDir } = await stage(slug, {}, commits);
		try {
			const result = await script(
				nodePath.join(root, "repo"),
				new Map(),
				metadata,
				contextDir,
				changeDir,
			);
			assert.equal(result.hints.length, 12);
			assert.equal(result.directions.length, 2);
			const first = result.hints[0];
			const bare = result.hints[3];
			assert.ok(first && bare);
			assert.equal(first.file, "inputs/context/commits.json");
			assert.equal(first.pattern, "commit");
			assert.equal(first.context, "0000000 Add step 0 and wire it");
			// The row cites the line of commits.json that carries the commit's sha.
			assert.ok(first.line > 0);
			assert.deepEqual(first.flags, {
				bare: false,
				repeat: false,
				conjoined: true,
				cutOff: false,
				bodyLines: 0,
				files: 2,
				paths: "App/Step0.swift, README.md",
				kinds: "App/, .md",
			});
			assert.equal(bare.flags.bare, true);
		} finally {
			rmSync(root, { recursive: true, force: true });
		}
	}
});

void test("an issue reference is resolved against the inventory and the linked items", async () => {
	const { root, script, contextDir, changeDir } = await stage("links-the-change-to-its-issue", {
		"linked_work_items.json": { workItems: [{ number: 18, title: "Add ingredients", body: "" }] },
		"project_inventory.json": {
			issues: [
				{ number: 18, title: "Add ingredients", state: "OPEN" },
				{ number: 21, title: "Confetti", state: "CLOSED" },
			],
		},
	});
	try {
		const result = await script(
			nodePath.join(root, "repo"),
			new Map(),
			{ ...metadata, body: "Closes #18\n\nSee also #21 and #99." },
			contextDir,
			changeDir,
		);
		// The first row for a number is the title's; the same number in the body and branch follows.
		const byNumber = new Map(result.hints.toReversed().map((h) => [h.flags.number, h.flags]));
		assert.deepEqual(byNumber.get(18), {
			number: 18,
			where: "inputs/context/metadata.json (title)",
			inInventory: true,
			title: "Add ingredients",
			state: "OPEN",
			inLinkedItems: true,
		});
		assert.equal(byNumber.get(21)?.inInventory, true);
		assert.equal(byNumber.get(21)?.inLinkedItems, false);
		assert.equal(byNumber.get(99)?.inInventory, false);
		assert.equal(byNumber.get(99)?.title, "");
		// The provider's enum casing is the one the inventory holds.
		assert.equal(result.metrics.inventoryOpenIssues, 1);
		assert.equal(result.metrics.referencesInInventory, 2);
		assert.equal(result.metrics.linkedWorkItems, 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("every reviewer comment is one row with the reply, the thread and the later commits beside it", async () => {
	const { root, script, contextDir, changeDir } = await stage(
		"engaging-with-inline-review-comments",
		{
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
		},
		[
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
		],
	);
	try {
		const result = await script(
			nodePath.join(root, "repo"),
			new Map([diffFile("App/AddIngredientView.swift", [54, 56])]),
			metadata,
			contextDir,
			changeDir,
		);
		assert.equal(result.hints.length, 2);
		const [stock, rename] = result.hints;
		assert.ok(stock && rename);
		assert.equal(stock.pattern, "reviewer comment");
		assert.deepEqual(stock.flags, {
			by: "jennifer",
			bot: false,
			at: "2026-04-13T14:18:20Z",
			side: "RIGHT",
			outdated: false,
			authorReplied: true,
			replyExcerpt: "Good idea, done in the next commit — it now turns the field red.",
			threadResolved: true,
			resolvedBy: "ada",
			fileInChange: true,
			changeNearLine: true,
			commitsAfter: 2,
			commitsAfterTouchingFile: 1,
		});
		assert.equal(rename.flags.bot, true);
		assert.equal(rename.flags.outdated, true);
		assert.equal(rename.flags.authorReplied, false);
		assert.equal(rename.flags.threadResolved, false);
		assert.equal(result.metrics.reviewerComments, 1);
		assert.equal(result.metrics.botComments, 1);
		assert.match(
			result.directions[0] ?? "",
			/^1 reviewer comment\(s\) \(1 more by bots\); 1 have a later author reply in the thread; 1 have a later authored commit touching the file/u,
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a comments file that was not captured is told apart from one with no comment by others", async () => {
	const absent = await stage("defers-review-asks-into-tracked-work", {});
	const empty = await stage("defers-review-asks-into-tracked-work", {
		"comments.json": [],
		"general_comments.json": {
			comments: [{ author: "ada", body: "Ready", createdAt: "2026-04-13T14:00:00Z" }],
		},
	});
	const engagingAbsent = await stage("engaging-with-inline-review-comments", {});
	const engagingEmpty = await stage("engaging-with-inline-review-comments", {
		"comments.json": [],
	});
	try {
		const run = async (staged: typeof absent) =>
			staged.script(
				nodePath.join(staged.root, "repo"),
				new Map(),
				metadata,
				staged.contextDir,
				staged.changeDir,
			);
		const noFile = await run(absent);
		assert.match(noFile.directions[0] ?? "", /^No comments file was captured/u);
		assert.equal(noFile.metrics.commentsFileAbsent, 1);
		const noOthers = await run(empty);
		assert.match(
			noOthers.directions[0] ?? "",
			/^No comment by anyone other than the author in the captured record \(0 inline comment\(s\), 1 conversation comment\(s\)\)/u,
		);
		assert.equal(noOthers.metrics.commentsFileAbsent, 0);
		const inlineNoFile = await run(engagingAbsent);
		assert.match(inlineNoFile.directions[0] ?? "", /^No comments file was captured/u);
		const inlineNoOthers = await run(engagingEmpty);
		assert.match(
			inlineNoOthers.directions[0] ?? "",
			/^No inline comment by anyone other than the author among the 0 captured/u,
		);
	} finally {
		for (const staged of [absent, empty, engagingAbsent, engagingEmpty]) {
			rmSync(staged.root, { recursive: true, force: true });
		}
	}
});

void test("the merge practices read the threads and decisions as rows against the merge", async () => {
	const record = {
		"review_threads.json": {
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
				// Resolved, but after the merge: open at the merge, which is what the practice asks.
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
				{ state: "APPROVED", author: "tom", submittedAt: "2026-04-13T16:00:00Z" },
			],
		},
	};
	const merged = { ...metadata, is_merged: true, merged_by: "ada", review_decision: "APPROVED" };
	const threads = await stage("merged-past-unresolved-review-threads", record);
	const approval = await stage("merges-only-after-approval", record);
	const noRecord = await stage("merges-only-after-approval", {});
	try {
		const unresolved = await threads.script(
			nodePath.join(threads.root, "repo"),
			new Map(),
			merged,
			threads.contextDir,
			threads.changeDir,
		);
		assert.equal(unresolved.metrics.unresolvedThreads, 2);
		assert.equal(unresolved.metrics.resolvedThreads, 1);
		assert.equal(unresolved.metrics.mergedByIsAuthor, 1);
		assert.equal(unresolved.metrics.threadsFileAbsent, 0);
		const [merge, thread] = unresolved.hints;
		assert.ok(merge && thread);
		assert.equal(merge.pattern, "merge");
		assert.equal(merge.flags.mergedBy, "ada");
		assert.equal(merge.flags.mergedByIsAuthor, true);
		assert.equal(merge.flags.reviewDecision, "APPROVED");
		assert.equal(thread.pattern, "unresolved thread");
		assert.deepEqual(thread.flags, {
			id: 11,
			state: "UNRESOLVED",
			createdAt: "2026-04-13T14:19:00Z",
			resolvedAt: "",
			resolvedBy: "",
			path: "App/Model.swift",
			line: 9,
			outdated: true,
		});
		const late = unresolved.hints[2];
		assert.ok(late);
		assert.equal(late.pattern, "thread resolved after the merge");
		assert.equal(late.flags.resolvedAt, "2026-04-13T16:00:00Z");
		assert.match(
			unresolved.directions[0] ?? "",
			/^Merged by ada \(the author\); 3 thread\(s\) captured, 2 open at the merge/u,
		);

		const decisions = await approval.script(
			nodePath.join(approval.root, "repo"),
			new Map(),
			merged,
			approval.contextDir,
			approval.changeDir,
		);
		assert.equal(decisions.metrics.decisions, 4);
		// Jennifer's approval at 15:00 is before the 15:06 merge and by someone else; Ada's is the author's, Tom's is after.
		assert.equal(decisions.metrics.approvalsBeforeMergeByOthers, 1);
		const rows = decisions.hints.filter((h) => h.pattern === "review decision");
		assert.deepEqual(
			rows.map((h) => [h.flags.author, h.flags.state, h.flags.beforeMerge, h.flags.isAuthor]),
			[
				["jennifer", "CHANGES_REQUESTED", true, false],
				["ada", "APPROVED", true, true],
				["jennifer", "APPROVED", true, false],
				["tom", "APPROVED", false, false],
			],
		);
		assert.match(
			rows[0]?.context ?? "",
			/^jennifer: CHANGES_REQUESTED — Please fix the stock check$/u,
		);
		const last = decisions.hints.filter((h) => h.pattern === "last decision by reviewer");
		assert.deepEqual(
			last.map((h) => [h.flags.author, h.flags.state]),
			[
				["jennifer", "APPROVED"],
				["ada", "APPROVED"],
				["tom", "APPROVED"],
			],
		);

		const absent = await noRecord.script(
			nodePath.join(noRecord.root, "repo"),
			new Map(),
			merged,
			noRecord.contextDir,
			noRecord.changeDir,
		);
		assert.equal(absent.metrics.threadsFileAbsent, 1);
		assert.match(absent.directions[0] ?? "", /^No review threads file was captured/u);
		const open = await noRecord.script(
			nodePath.join(noRecord.root, "repo"),
			new Map(),
			{ ...metadata, state: "OPEN", merged_at: undefined },
			noRecord.contextDir,
			noRecord.changeDir,
		);
		assert.match(open.directions[0] ?? "", /not merged/u);
	} finally {
		for (const staged of [threads, approval, noRecord]) {
			rmSync(staged.root, { recursive: true, force: true });
		}
	}
});
