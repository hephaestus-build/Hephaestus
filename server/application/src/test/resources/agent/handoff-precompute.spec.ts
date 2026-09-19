import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { isPracticeModule } from "../../../../../../docker/agents/precompute/lib/practice-contract.ts";

const repositoryRoot = fileURLToPath(new URL("../../../../../../", import.meta.url));

/**
 * Stages the script beside a `lib/` link, as the runner does, so its runtime `../lib` imports resolve.
 * The change view is written where the script reads it: `work/change/commits.json` under the root.
 */
async function stage(commits: Array<{ sha: string; message: string }>) {
	const root = mkdtempSync(join(tmpdir(), "handoff-precompute-"));
	mkdirSync(join(root, "practices"));
	mkdirSync(join(root, "work/change"), { recursive: true });
	writeFileSync(join(root, "package.json"), '{"type":"module"}\n');
	symlinkSync(join(repositoryRoot, "docker/agents/precompute/lib"), join(root, "lib"));
	writeFileSync(join(root, "work/change/commits.json"), JSON.stringify({ commits }));
	const staged = join(root, "practices/ready-and-traceable-handoff.ts");
	cpSync(
		join(
			repositoryRoot,
			"server/application/src/main/resources/practices/precompute/ready-and-traceable-handoff.ts",
		),
		staged,
	);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) throw new Error("script does not export a default function");
	return { root, script: mod.default, changeDir: join(root, "work/change") };
}

const metadata = {
	pr_number: 1,
	pr_url: "https://example.org/team/project/pull/1",
	repository_full_name: "team/project",
	source_branch: "42-quiz-flow",
	target_branch: "main",
	commit_sha: "abc123",
	body: "Closes #42\n- [x] Tested locally\nOpen the quiz and finish ten questions.",
};

void test("a testing checklist does not turn a traceable handoff into a test-absence claim", async () => {
	const { root, script, changeDir } = await stage([]);
	try {
		const result = await script(
			join(root, "repo"),
			new Map(),
			metadata,
			join(root, "context"),
			changeDir,
		);
		assert.equal(result.metrics.issueMentionSyntaxCandidateCount, 1);
		assert.match(result.directions[0] ?? "", /#42/);
		// The checklist is counted as written, so a tick is a fact rather than a guess.
		assert.equal(result.metrics.checklistTicked, 1);
		assert.equal(result.metrics.checklistUnticked, 0);
		assert.match(result.directions[1] ?? "", /1 ticked and 0 unticked/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a reference in a commit message of the change counts, read from the container's change view", async () => {
	const { root, script, changeDir } = await stage([
		{ sha: "a".repeat(40), message: "Fix quiz flow\n\nRefs #7" },
	]);
	try {
		const result = await script(
			join(root, "repo"),
			new Map(),
			{ ...metadata, body: "No mention here.", source_branch: "quiz-flow" },
			join(root, "context"),
			changeDir,
		);
		assert.equal(result.metrics.issueMentionSyntaxCandidateCount, 1);
		assert.equal(result.metrics.commitCount, 1);
		assert.match(result.directions[0] ?? "", /#7/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
