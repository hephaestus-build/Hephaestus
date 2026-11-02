import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";

import { commitInput, createCommit, stageChanges } from "./commit-via-api.ts";
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
			body: "Requested by octocat in https://github.com/o/r/actions/runs/1/attempts/1",
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
		message: {
			headline: "chore(deploy): channels/staging.json -> v1.2.3",
			body: "Requested by octocat in https://github.com/o/r/actions/runs/1/attempts/1",
		},
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

const BASE = "b".repeat(40);
const COMMIT = "c".repeat(40);
const OTHER = "d".repeat(40);
/** `git hash-object` of the contents that the input below commits. */
const NEW_BLOB = "3b18e512dba79e4c8300dd08aeb37f8e728b8dad";
const input = commitInput(
	{ repository: "o/r", branch: "work", expectedHeadOid: BASE, headline: "Move the file" },
	new Map([["new.txt", Buffer.from("hello world\n")]]),
	["old.txt"],
);
const baseFiles = [
	{ path: "old.txt", type: "blob", sha: "1".repeat(40) },
	{ path: "kept", type: "tree", sha: "2".repeat(40) },
	{ path: "kept/file.txt", type: "blob", sha: "3".repeat(40) },
];
const landedFiles = [
	{ path: "kept", type: "tree", sha: "2".repeat(40) },
	{ path: "kept/file.txt", type: "blob", sha: "3".repeat(40) },
	{ path: "new.txt", type: "blob", sha: NEW_BLOB },
];

interface FakeHead {
	readonly oid: string;
	readonly parent?: string;
	readonly headline?: string;
	readonly files?: readonly object[];
	readonly verified?: boolean;
}

/**
 * GitHub's answers for one branch. Each entry of `posts` answers one commit attempt: a status for a
 * failure, and `land` moves the branch before the answer, as a lost answer does.
 */
function fakeGitHub(
	context: TestContext,
	posts: readonly { status?: number; body?: string; land?: FakeHead; refuse?: boolean }[],
) {
	const state = { head: { oid: BASE } as FakeHead, posts: 0, reads: 0, waits: [] as number[] };
	context.mock.method(globalThis, "fetch", async (url: string | URL, init?: RequestInit) => {
		const address = String(url);
		if (init?.method === "POST") {
			const answer = posts[state.posts] ?? {};
			state.posts += 1;
			if (answer.land !== undefined) {
				state.head = answer.land;
			}
			if (answer.status !== undefined) {
				return new Response(answer.body ?? "", { status: answer.status });
			}
			if (answer.refuse === true) {
				return Response.json({
					errors: [{ message: "Expected branch to point to another commit" }],
				});
			}
			state.head = { oid: COMMIT, parent: BASE, headline: "Move the file", files: landedFiles };
			return Response.json({ data: { createCommitOnBranch: { commit: { oid: COMMIT } } } });
		}
		state.reads += 1;
		if (address.endsWith("/git/ref/heads/work")) {
			return Response.json({ object: { sha: state.head.oid } });
		}
		if (address.includes("/git/commits/")) {
			return Response.json({
				parents: [{ sha: state.head.parent ?? OTHER }],
				message: `${state.head.headline ?? "Another commit"}\n\nA body.`,
				verification: { verified: state.head.verified ?? true },
			});
		}
		const files = address.includes(`/git/trees/${BASE}`) ? baseFiles : state.head.files;
		return Response.json({ truncated: false, tree: files ?? [] });
	});
	const wait = async (milliseconds: number) => state.waits.push(milliseconds);
	return { state, wait };
}

const ours = { oid: COMMIT, parent: BASE, headline: "Move the file", files: landedFiles };

void test("the answer to the first attempt decides when GitHub answers", async (context) => {
	const { state, wait } = fakeGitHub(context, [{}]);
	const { oid } = await createCommit("token", input, wait);
	assert.equal(oid, COMMIT);
	assert.deepEqual([state.posts, state.reads, state.waits], [1, 0, []]);
});

void test("a transient failure that changed nothing commits again", async (context) => {
	const { state, wait } = fakeGitHub(context, [{ status: 502 }, {}]);
	const { oid } = await createCommit("token", input, wait);
	assert.equal(oid, COMMIT);
	assert.deepEqual([state.posts, state.waits], [2, [10_000]]);
});

void test("a lost answer to a commit that landed never commits twice", async (context) => {
	const { state, wait } = fakeGitHub(context, [{ status: 502, land: ours }]);
	const { oid } = await createCommit("token", input, wait);
	assert.equal(oid, COMMIT);
	assert.equal(state.posts, 1);
});

void test("a commit that lands after the look is still found", async (context) => {
	const { state, wait } = fakeGitHub(context, [{ status: 504 }, { refuse: true, land: ours }]);
	const { oid } = await createCommit("token", input, wait);
	assert.equal(oid, COMMIT);
	assert.equal(state.posts, 2);
});

void test("a branch that someone else moved stops the commit", async (context) => {
	for (const [label, moved] of [
		["another parent", { ...ours, oid: OTHER, parent: OTHER }],
		["another headline", { ...ours, oid: OTHER, headline: "Other" }],
		["other files", { ...ours, oid: OTHER, files: baseFiles }],
		["no verified signature", { ...ours, oid: OTHER, verified: false }],
	] as const) {
		const { state, wait } = fakeGitHub(context, [{ status: 502, land: moved }]);
		await assert.rejects(createCommit("token", input, wait), /moved to d+, which is not/u, label);
		assert.equal(state.posts, 1, label);
		context.mock.restoreAll();
	}
});

void test("a refused first attempt and a 4xx are not repeated", async (context) => {
	for (const answer of [
		{ refuse: true },
		{ status: 401 },
		{ status: 403, body: "API rate limit exceeded" },
		{ status: 404 },
		{ status: 422 },
		{ status: 429 },
	]) {
		const { state, wait } = fakeGitHub(context, [answer]);
		await assert.rejects(createCommit("token", input, wait));
		assert.deepEqual([state.posts, state.reads, state.waits], [1, 0, []]);
		context.mock.restoreAll();
	}
});

void test("a secondary rate limit is repeated", async (context) => {
	const limited = { status: 403, body: "You have exceeded a secondary rate limit" };
	const { state, wait } = fakeGitHub(context, [limited, {}]);
	const { oid } = await createCommit("token", input, wait);
	assert.equal(oid, COMMIT);
	assert.equal(state.posts, 2);
});

void test("repeated transient failures stop at the bound", async (context) => {
	const failed = { status: 503 };
	const { state, wait } = fakeGitHub(context, [failed, failed, failed, failed, failed]);
	await assert.rejects(createCommit("token", input, wait), /answered 503/u);
	assert.deepEqual([state.posts, state.waits], [4, [10_000, 30_000, 60_000]]);
});
