import assert from "node:assert/strict";
import { chmod, mkdtemp, mkdir, readFile, realpath, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import { output } from "./lib/process.ts";
import {
	parsePullRequest,
	rebaseGenerated,
	regenerateArtifacts,
	requireCleanBranch,
} from "./rebase-generated.ts";

const artifacts = [
	"server/openapi.yaml",
	"webapp/src/api/sdk.gen.ts",
	"extension/src/api/index.ts",
	"docs/admin/configuration-reference.mdx",
] as const;

async function fixture(t: TestContext) {
	const cwd = await mkdtemp(path.join(os.tmpdir(), "hephaestus-rebase-"));
	t.after(async () => {
		await rm(cwd, { recursive: true, force: true });
	});
	const git = async (...args: string[]) => {
		const value = await output("git", args, { cwd, env: environmentForGitFixture() });
		return value.trim();
	};
	const put = async (file: string, text: string) => {
		await mkdir(path.dirname(path.join(cwd, file)), { recursive: true });
		await writeFile(path.join(cwd, file), text);
	};
	await git("init", "--initial-branch=main");
	await git("config", "user.name", "Fixture");
	await git("config", "user.email", "fixture@example.test");
	await git("config", "commit.gpgsign", "false");
	await git("config", "core.hooksPath", path.join(cwd, "no-hooks"));
	const commit = async (message: string) => {
		await git("add", ".");
		await git("commit", "-m", message);
		return git("rev-parse", "HEAD");
	};
	await put("source.txt", "initial\n");
	for (const file of artifacts) {
		await put(file, "initial\n");
	}
	await commit("initial");
	await git("switch", "-c", "feature");
	return { cwd, git, put, commit };
}

await test("independent additions survive generated conflicts and regeneration replaces all four outputs", async (t) => {
	const f = await fixture(t);
	await f.put("feature.txt", "feature\n");
	for (const file of artifacts) {
		await f.put(file, "feature\n");
	}
	await f.commit("feature source and output");
	await f.put("second-feature.txt", "second feature\n");
	for (const file of artifacts) {
		await f.put(file, "second output\n");
	}
	const head = await f.commit("second source and output");
	await f.git("switch", "main");
	await f.put("upstream.txt", "upstream\n");
	for (const file of artifacts) {
		await f.put(file, "upstream\n");
	}
	const base = await f.commit("upstream source and output");
	await f.git("switch", "feature");
	let generations = 0;
	await rebaseGenerated(f.cwd, { head, branch: "feature", base: "main" }, base, async () => {
		generations += 1;
		const combined =
			(await readFile(path.join(f.cwd, "feature.txt"), "utf8")) +
			(await readFile(path.join(f.cwd, "upstream.txt"), "utf8"));
		for (const file of artifacts) {
			await f.put(file, combined);
		}
	});
	assert.equal(generations, 1);
	assert.equal(await f.git("merge-base", "HEAD", base), base);
	assert.equal(await f.git("log", "-1", "--format=%s"), "second source and output");
	assert.equal(await f.git("rev-list", "--count", `${base}..HEAD`), "2");
	assert.equal(await readFile(path.join(f.cwd, "second-feature.txt"), "utf8"), "second feature\n");
	for (const file of artifacts) {
		assert.equal(await readFile(path.join(f.cwd, file), "utf8"), "feature\nupstream\n");
	}
	assert.equal(await f.git("diff", "--cached", "--name-only"), "");
	const changed = await f.git("diff", "--name-only");
	assert.equal(changed.split("\n").length, 4);
});

await test("a handwritten conflict aborts the whole rebase and preserves the original head", async (t) => {
	const f = await fixture(t);
	await f.put("source.txt", "feature\n");
	await f.put(artifacts[0], "feature\n");
	const head = await f.commit("feature");
	await f.git("switch", "main");
	await f.put("source.txt", "upstream\n");
	await f.put(artifacts[0], "upstream\n");
	const base = await f.commit("upstream");
	await f.git("switch", "feature");
	await assert.rejects(
		rebaseGenerated(f.cwd, { head, branch: "feature", base: "main" }, base, async () => {
			assert.fail("must not regenerate after a handwritten conflict");
		}),
		/source.txt/u,
	);
	assert.equal(await f.git("rev-parse", "HEAD"), head);
	assert.equal(await f.git("status", "--porcelain"), "");
});

await test("a generated file deleted upstream is regenerated, and an empty generated commit is dropped", async (t) => {
	const f = await fixture(t);
	await f.put(artifacts[0], "feature\n");
	const head = await f.commit("only generated");
	await f.git("switch", "main");
	await f.git("rm", artifacts[0]);
	const base = await f.commit("delete old output");
	await f.git("switch", "feature");
	await rebaseGenerated(f.cwd, { head, branch: "feature", base: "main" }, base, async () => {
		await f.put(artifacts[0], "regenerated\n");
	});
	assert.equal(await f.git("rev-parse", "HEAD"), base);
	assert.equal(await readFile(path.join(f.cwd, artifacts[0]), "utf8"), "regenerated\n");
});

await test("dirty, staged and untracked files, and a mismatched branch or head, are preserved", async (t) => {
	const f = await fixture(t);
	const head = await f.git("rev-parse", "HEAD");
	const pr = { head, branch: "feature", base: "main" };
	for (const file of ["source.txt", "untracked.txt"]) {
		await f.put(file, "local work\n");
		await assert.rejects(requireCleanBranch(f.cwd, pr), /clean/u);
		assert.equal(await readFile(path.join(f.cwd, file), "utf8"), "local work\n");
	}
	await f.git("add", "source.txt");
	await assert.rejects(requireCleanBranch(f.cwd, pr), /clean/u);
	await f.commit("keep local work");
	await assert.rejects(requireCleanBranch(f.cwd, pr), /recorded remote head/u);
	await assert.rejects(
		requireCleanBranch(f.cwd, { ...pr, head: await f.git("rev-parse", "HEAD"), branch: "wrong" }),
		/recorded remote head/u,
	);
});

await test("an existing rebase is left untouched", async (t) => {
	const f = await fixture(t);
	await f.put("source.txt", "feature\n");
	const head = await f.commit("feature");
	await f.git("switch", "main");
	await f.put("source.txt", "upstream\n");
	const base = await f.commit("upstream");
	await f.git("switch", "feature");
	await assert.rejects(f.git("rebase", base));
	const before = await f.git("status", "--porcelain");
	await assert.rejects(
		requireCleanBranch(f.cwd, { head, branch: "feature", base: "main" }),
		/existing rebase/u,
	);
	assert.equal(await f.git("status", "--porcelain"), before);
	await f.git("rebase", "--abort");
	assert.equal(await f.git("rev-parse", "HEAD"), head);
});

await test("generator failure preserves the rebased branch and partial output for diagnosis", async (t) => {
	const f = await fixture(t);
	await f.put("feature.txt", "feature\n");
	const head = await f.commit("feature");
	await f.git("switch", "main");
	await f.put("upstream.txt", "upstream\n");
	const base = await f.commit("upstream");
	await f.git("switch", "feature");
	await assert.rejects(
		rebaseGenerated(f.cwd, { head, branch: "feature", base: "main" }, base, async () => {
			await f.put(artifacts[0], "partial output\n");
			throw new Error("generation failed");
		}),
		/generation failed/u,
	);
	assert.equal(await f.git("merge-base", "HEAD", base), base);
	assert.equal(await readFile(path.join(f.cwd, artifacts[0]), "utf8"), "partial output\n");
});

await test("queued, forked, closed, malformed and non-main pull requests fail closed", () => {
	const pr = {
		state: "OPEN",
		mergeQueueEntry: null,
		headRefOid: "a".repeat(40),
		headRefName: "feature",
		baseRefName: "main",
		headRepository: { nameWithOwner: "owner/repo" },
	};
	const parse = (changes: Record<string, unknown>) =>
		parsePullRequest(
			{ data: { repository: { pullRequest: { ...pr, ...changes } } } },
			"owner/repo",
		);
	assert.equal(parse({}).branch, "feature");
	for (const changes of [
		{ mergeQueueEntry: { id: "queued" } },
		{ mergeQueueEntry: undefined },
		{ state: "CLOSED" },
		{ headRepository: { nameWithOwner: "fork/repo" } },
		{ headRefOid: "bad" },
		{ baseRefName: "release" },
	]) {
		assert.throws(() => parse(changes));
	}
});

await test("an external JAR and a branch with merge commits are refused before rewriting", async (t) => {
	const f = await fixture(t);
	const originalJar = process.env.HEPHAESTUS_APPLICATION_JAR;
	try {
		process.env.HEPHAESTUS_APPLICATION_JAR = "stale.jar";
		await assert.rejects(
			requireCleanBranch(f.cwd, {
				head: await f.git("rev-parse", "HEAD"),
				branch: "feature",
				base: "main",
			}),
			/Unset HEPHAESTUS_APPLICATION_JAR/u,
		);
	} finally {
		if (originalJar === undefined) {
			delete process.env.HEPHAESTUS_APPLICATION_JAR;
		} else {
			process.env.HEPHAESTUS_APPLICATION_JAR = originalJar;
		}
	}
	await f.git("switch", "-c", "side");
	await f.put("side.txt", "side\n");
	await f.commit("side");
	await f.git("switch", "feature");
	await f.git("merge", "--no-ff", "side", "-m", "merge side");
	const head = await f.git("rev-parse", "HEAD");
	const base = await f.git("rev-parse", "main");
	await assert.rejects(
		rebaseGenerated(f.cwd, { head, branch: "feature", base: "main" }, base, async () => {
			assert.fail("must not regenerate a refused branch");
		}),
		/merge commits/u,
	);
	assert.equal(await f.git("rev-parse", "HEAD"), head);
});

await test("a saved handwritten conflict resolution cannot bypass the conflict guard", async (t) => {
	const f = await fixture(t);
	await f.git("config", "rerere.enabled", "true");
	await f.git("config", "rerere.autoupdate", "true");
	await f.put("source.txt", "feature\n");
	await f.put(artifacts[0], "feature\n");
	const head = await f.commit("feature");
	await f.git("switch", "main");
	await f.put("source.txt", "upstream\n");
	await f.put(artifacts[0], "upstream\n");
	const base = await f.commit("upstream");
	await f.git("switch", "feature");
	await assert.rejects(f.git("rebase", base));
	await f.put("source.txt", "previous resolution\n");
	await f.put(artifacts[0], "previous resolution\n");
	await f.git("rerere");
	await f.git("rebase", "--abort");
	await assert.rejects(f.git("rebase", base));
	assert.equal(await f.git("diff", "--name-only", "--diff-filter=U"), "");
	assert.equal(await readFile(path.join(f.cwd, "source.txt"), "utf8"), "previous resolution\n");
	await f.git("rebase", "--abort");
	await assert.rejects(
		rebaseGenerated(f.cwd, { head, branch: "feature", base: "main" }, base, async () => {
			assert.fail("must not accept the saved handwritten resolution");
		}),
		/source.txt/u,
	);
	assert.equal(await f.git("rev-parse", "HEAD"), head);
	assert.equal(await f.git("status", "--porcelain"), "");
});

await test("a rebase that cannot start leaves the original branch intact", async (t) => {
	const f = await fixture(t);
	await f.put("feature.txt", "feature\n");
	await f.commit("feature");
	await f.git("switch", "main");
	await f.put("upstream.txt", "upstream\n");
	const base = await f.commit("upstream");
	await f.git("switch", "feature");
	await f.put("no-hooks/pre-rebase", "#!/usr/bin/env node\nprocess.exit(42);\n");
	await chmod(path.join(f.cwd, "no-hooks/pre-rebase"), 0o755);
	await f.git("add", "no-hooks/pre-rebase");
	await f.git("commit", "--amend", "--no-edit");
	const guardedHead = await f.git("rev-parse", "HEAD");
	await assert.rejects(
		rebaseGenerated(
			f.cwd,
			{ head: guardedHead, branch: "feature", base: "main" },
			base,
			async () => {
				assert.fail("must not regenerate after Git cannot start");
			},
		),
		/could not start/u,
	);
	assert.equal(await f.git("rev-parse", "HEAD"), guardedHead);
	assert.equal(await f.git("status", "--porcelain"), "");
});

await test("a script invoked from another checkout builds and generates from the target checkout", async (t) => {
	const f = await fixture(t);
	await f.put("package.json", '{"type":"module"}');
	await f.put(
		"node_modules/vite-plus/package.json",
		'{"type":"module","exports":{"./bin":{"import":"./bin.mjs"}}}',
	);
	await f.put(
		"node_modules/vite-plus/bin.mjs",
		`import { writeFile } from 'node:fs/promises';
if (process.argv[2] === 'run') await writeFile(process.argv[3] === 'generate:api' ? 'api-result' : 'config-result', process.env.HEPHAESTUS_APPLICATION_JAR);`,
	);
	await f.put(
		"scripts/lib/process.ts",
		`import { fileURLToPath } from 'node:url';
export const repositoryCli = () => fileURLToPath(import.meta.resolve('vite-plus/bin'));`,
	);
	await f.put(
		"scripts/lib/application-jar.ts",
		`import { writeFile } from 'node:fs/promises';
export async function executableJar() { await writeFile(new URL('../../builder-ran', import.meta.url), import.meta.dirname); return 'target-checkout.jar'; }`,
	);
	await regenerateArtifacts(f.cwd);
	assert.equal(
		await readFile(path.join(f.cwd, "builder-ran"), "utf8"),
		await realpath(path.join(f.cwd, "scripts/lib")),
	);
	for (const file of ["api-result", "config-result"]) {
		assert.equal(await readFile(path.join(f.cwd, file), "utf8"), "target-checkout.jar");
	}
});
