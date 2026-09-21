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
	commits: { sha: string; message: string; authoredAt: string }[] = [],
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
		nodePath.join(root, "work/change/commits.json"),
		JSON.stringify({
			commits: commits.map((c) => ({
				...c,
				author: "ada",
				committer: "ada",
				committedAt: c.authoredAt,
				parents: [],
			})),
		}),
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
				reviewDecisions: [],
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
		// The stock ask: the file is in the change but nothing near line 55 changed, no reply, resolved.
		assert.equal(stock.flags.fileInChange, true);
		assert.equal(stock.flags.changeNearLine, false);
		assert.equal(stock.flags.authorReplied, false);
		assert.equal(stock.flags.threadResolved, true);
		// The capitalisation ask: changed beside it, and the author answered.
		assert.equal(capitalisation.flags.changeNearLine, true);
		assert.equal(capitalisation.flags.authorReplied, true);
		// The conversation ask: the reply names an issue and defers in words.
		assert.equal(confetti.pattern, "conversation ask");
		assert.equal(confetti.flags.issueNamedInReply, true);
		assert.equal(confetti.flags.deferralWordsInReply, true);
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
