import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { stageChanges, commitInput } from "./commit-via-api.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";

type Git = (...args: string[]) => string;

function repoWithCommittedFiles() {
	const repo = mkdtempSync(path.join(tmpdir(), "commit-via-api-"));
	const env = environmentForGitFixture();
	const git: Git = (...args) =>
		execFileSync("git", args, {
			cwd: repo,
			encoding: "utf8",
			stdio: ["ignore", "pipe", "pipe"],
			env,
		});
	git("init", "--quiet", "--initial-branch=main");
	git("config", "commit.gpgsign", "false");
	git("config", "core.hooksPath", path.join(repo, ".git", "disabled-hooks"));
	git("config", "user.email", "test@example.invalid");
	git("config", "user.name", "Test");
	for (const file of ["kept.txt", "edited.txt", "removed.txt", "moved.txt"]) {
		writeFileSync(path.join(repo, file), `${file}\n`);
	}
	git("add", "-A");
	git("commit", "--quiet", "-m", "initial");
	return {
		repo,
		git,
		changes: async (...paths: string[]) => stageChanges(paths, { cwd: repo, env }),
	};
}

await test("enumerates every added, modified and deleted path in the work tree", async (t) => {
	const { repo, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	writeFileSync(path.join(repo, "edited.txt"), "edited\n");
	rmSync(path.join(repo, "removed.txt"));
	mkdirSync(path.join(repo, "generated"));
	writeFileSync(path.join(repo, "generated/added.txt"), "added\n");

	assert.deepEqual(await changes(), {
		additions: ["edited.txt", "generated/added.txt"],
		deletions: ["removed.txt"],
	});
});

await test("splits a rename into the source deleted and the destination added", async (t) => {
	const { repo, git, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	git("mv", "moved.txt", "arrived.txt");

	assert.deepEqual(await changes(), {
		additions: ["arrived.txt"],
		deletions: ["moved.txt"],
	});
});

await test("has nothing to commit from a clean work tree", async (t) => {
	const { repo, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));

	assert.deepEqual(await changes(), { additions: [], deletions: [] });
});

await test("refuses scoped conflicts without staging them", async (t) => {
	const { repo, git, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	git("checkout", "-qb", "other");
	writeFileSync(path.join(repo, "edited.txt"), "other\n");
	git("commit", "-qam", "other");
	git("checkout", "-q", "main");
	writeFileSync(path.join(repo, "edited.txt"), "main\n");
	git("commit", "-qam", "main");
	assert.throws(() => git("merge", "other"));
	await assert.rejects(changes("edited.txt"), /unmerged/u);
	assert.equal(git("diff", "--name-only", "--diff-filter=U"), "edited.txt\n");
	mkdirSync(path.join(repo, "generated"));
	writeFileSync(path.join(repo, "generated/added.txt"), "added\n");
	assert.deepEqual(await changes("generated"), {
		additions: ["generated/added.txt"],
		deletions: [],
	});
});

await test("builds the mutation input, with file contents base64-encoded", () => {
	const input = commitInput(
		{
			repository: "hephaestus-build/Hephaestus",
			branch: "deploy-state",
			expectedHeadOid: "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c",
			headline: "chore(deploy): channels/staging.json -> v1.2.3",
		},
		new Map([["channels/staging.json", Buffer.from('{"release":"v1.2.3"}\n')]]),
		["channels/retired.json"],
	);

	assert.deepEqual(input, {
		branch: {
			repositoryNameWithOwner: "hephaestus-build/Hephaestus",
			branchName: "deploy-state",
		},
		expectedHeadOid: "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c",
		message: { headline: "chore(deploy): channels/staging.json -> v1.2.3" },
		fileChanges: {
			additions: [{ path: "channels/staging.json", contents: "eyJyZWxlYXNlIjoidjEuMi4zIn0K" }],
			deletions: [{ path: "channels/retired.json" }],
		},
	});
	assert.equal(
		Buffer.from(input.fileChanges.additions[0]?.contents ?? "", "base64").toString("utf8"),
		'{"release":"v1.2.3"}\n',
	);
});

await test("a staged new file removed before committing is not a deletion on the branch", async (t) => {
	const { repo, git, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	writeFileSync(path.join(repo, "temporary.txt"), "temporary\n");
	git("add", "temporary.txt");
	rmSync(path.join(repo, "temporary.txt"));
	assert.deepEqual(await changes(), { additions: [], deletions: [] });
});

await test("a renamed file removed before committing deletes only its original path", async (t) => {
	const { repo, git, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	git("mv", "moved.txt", "arrived.txt");
	rmSync(path.join(repo, "arrived.txt"));
	assert.deepEqual(await changes(), { additions: [], deletions: ["moved.txt"] });
});

await test("recreating a staged deletion or rename replaces the original contents", async (t) => {
	const { repo, git, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	git("rm", "removed.txt");
	git("mv", "moved.txt", "arrived.txt");
	writeFileSync(path.join(repo, "removed.txt"), "replacement\n");
	writeFileSync(path.join(repo, "moved.txt"), "replacement\n");
	assert.deepEqual(await changes(), {
		additions: ["arrived.txt", "moved.txt", "removed.txt"],
		deletions: [],
	});
});

await test("staged edits reverted in the work tree produce no commit", async (t) => {
	const { repo, git, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	writeFileSync(path.join(repo, "edited.txt"), "staged\n");
	git("add", "edited.txt");
	writeFileSync(path.join(repo, "edited.txt"), "edited.txt\n");
	assert.deepEqual(await changes(), { additions: [], deletions: [] });
});

await test("commits only selected generated paths, excluding staged and unstaged unrelated edits", async (t) => {
	const { repo, git, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	writeFileSync(path.join(repo, "edited.txt"), "staged\n");
	git("add", "edited.txt");
	writeFileSync(path.join(repo, "kept.txt"), "unstaged\n");
	mkdirSync(path.join(repo, "generated"));
	writeFileSync(path.join(repo, "generated/added.txt"), "added\n");
	assert.deepEqual(await changes("generated"), {
		additions: ["generated/added.txt"],
		deletions: [],
	});
	assert.equal(git("diff", "--name-only"), "kept.txt\n");
});

await test("preserves filenames that require Git's NUL-delimited output", async (t) => {
	const { repo, changes } = repoWithCommittedFiles();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	const paths = ["space name.txt", ...(process.platform === "win32" ? [] : ["line\nbreak.txt"])];
	for (const file of paths) {
		writeFileSync(path.join(repo, file), "added\n");
	}
	assert.deepEqual(await changes(), { additions: paths.toSorted(), deletions: [] });
});
