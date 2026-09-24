import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { subjectFacts } from "../../../../../../docker/agents/precompute/lib/commit-subjects.ts";
import { isPracticeModule } from "../../../../../../docker/agents/precompute/lib/practice-contract.ts";

const repositoryRoot = fileURLToPath(new URL("../../../../../../", import.meta.url));

const commit = (sha: string, message: string, parents = ["p"]) => ({
	sha,
	message,
	author: "a",
	authoredAt: "",
	committer: "a",
	committedAt: "",
	parents,
	files: [],
	line: 0,
});

void test("a subject's shape is a fact — bare, repeated, listed, cut off — and a merge is set aside", () => {
	const facts = subjectFacts([
		commit("1111111", "Add button to start run\n"),
		commit("2222222", "fix\n"),
		commit("3333333", "Add button to start run\n"),
		commit("4444444", "add location manager, SwiftData persistence, and UI color cleanup\n"),
		commit("5555555", "Add support for\n"),
		commit("6666666", "Merge branch 'main' into feature\n", ["p1", "p2"]),
		commit(
			"7777777",
			"Refactor ProfileViewModel\n\n- remove stored context\n- add logging\n- add enum\n",
		),
		commit("8888888", "update readme\n"),
	]);
	const by = Object.fromEntries(facts.map((f) => [f.sha, f]));
	assert.deepEqual(
		[by["1111111"]?.bare, by["2222222"]?.bare, by["8888888"]?.bare],
		[false, true, false],
	);
	assert.equal(by["3333333"]?.repeat, true);
	assert.equal(by["4444444"]?.conjoined, true);
	assert.equal(by["7777777"]?.conjoined, true);
	assert.equal(by["1111111"]?.conjoined, false);
	assert.equal(by["5555555"]?.cutOff, true);
	assert.equal(by["6666666"]?.merge, true);
});

async function stage(slug: string, commits: unknown[]) {
	const root = mkdtempSync(path.join(tmpdir(), "commit-precompute-"));
	mkdirSync(path.join(root, "practices"));
	mkdirSync(path.join(root, "context"), { recursive: true });
	writeFileSync(path.join(root, "package.json"), '{"type":"module"}\n');
	symlinkSync(path.join(repositoryRoot, "docker/agents/precompute/lib"), path.join(root, "lib"));
	writeFileSync(path.join(root, "context/commits.json"), JSON.stringify({ commits }));
	const staged = path.join(root, `practices/${slug}.ts`);
	cpSync(
		path.join(
			repositoryRoot,
			`server/application/src/main/resources/practices/precompute/${slug}.ts`,
		),
		staged,
	);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) {
		throw new Error("script does not export a default function");
	}
	const script: typeof mod.default = async (repo, diff, metadata, contextDir, changeDir) => {
		const result = await mod.default(
			repo,
			diff,
			metadata,
			contextDir,
			changeDir,
			"areas/changed-work",
		);
		return result;
	};
	return { root, script, contextDir: path.join(root, "context") };
}

const metadata = {
	pr_number: 1,
	pr_url: "https://example.org/team/project/pull/1",
	repository_full_name: "team/project",
	source_branch: "f",
	target_branch: "main",
	commit_sha: "abc123",
};

void test("both commit practices read the subjects from the commit record and state shapes, not verdicts", async () => {
	const commits = [
		commit("1111111", "Add button to start run\n"),
		commit("2222222", "add location manager, SwiftData persistence, and UI color cleanup\n"),
		commit("3333333", "Merge branch 'main'\n", ["p1", "p2"]),
	];
	for (const slug of ["commit-subjects-explain-each-change", "commits-are-atomic-and-cohesive"]) {
		const { root, script, contextDir } = await stage(slug, commits);
		try {
			const result = await script(path.join(root, "repo"), new Map(), metadata, contextDir);
			assert.equal(result.metrics.authoredCommits, 2);
			assert.equal(result.metrics.mergeCommits, 1);
			assert.match(
				result.directions[0] ?? "",
				/2 authored commit\(s\), 1 merge commit\(s\) excluded/u,
			);
			// One record row per authored commit; the merge is set aside.
			assert.deepEqual(
				result.hints.map((h) => [h.file, h.pattern, h.context, h.flags.conjoined]),
				[
					["areas/changed-work/commits.json", "commit", "1111111 Add button to start run", false],
					[
						"areas/changed-work/commits.json",
						"commit",
						"2222222 add location manager, SwiftData persistence, and UI color cleanup",
						true,
					],
				],
			);
		} finally {
			rmSync(root, { recursive: true, force: true });
		}
	}
});
