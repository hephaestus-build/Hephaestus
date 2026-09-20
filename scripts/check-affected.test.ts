import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { changedPaths, commandsFor, parseBase, scopesFor } from "./check-affected.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";

await test("accepts only the documented arguments", () => {
	assert.equal(parseBase([]), "origin/main");
	assert.equal(parseBase(["--base", "upstream/trunk"]), "upstream/trunk");
	for (const args of [["--base"], ["--base", ""], ["--unknown"], ["--base", "main", "extra"]]) {
		assert.throws(() => parseBase(args), /Usage:/u);
	}
});

await test("selects ordinary workspace changes", () => {
	assert.deepEqual(scopesFor(["webapp/src/a.tsx"]), ["webapp"]);
	assert.deepEqual(scopesFor(["server/application/src/main/java/A.java"]), ["server"]);
});

await test("combines independent workspaces", () => {
	assert.deepEqual(scopesFor(["webapp/src/a.tsx", "server/application/src/main/java/A.java"]), [
		"server",
		"webapp",
	]);
});

await test("selects the Node runtime and precompute trees", () => {
	assert.deepEqual(
		scopesFor([
			"server/application/src/main/resources/agent/main.ts",
			"docker/agents/precompute/a.ts",
		]),
		["agents"],
	);
});

await test("selects documentation changes", () => {
	assert.deepEqual(scopesFor(["docs/contributor/example.mdx"]), ["docs"]);
	assert.deepEqual(scopesFor(["docs/images/readme/example.png"]), ["docs", "webapp"]);
});

await test("maps scopes to the documented commands", () => {
	assert.deepEqual(commandsFor(["agents", "docs", "server", "webapp"]), [
		["vp", "run", "affected:agents"],
		["vp", "run", "affected:docs"],
		["vp", "run", "affected:server"],
		["vp", "run", "affected:webapp"],
	]);
});

await test("fails closed for shared, generated, contract, tooling, and unknown inputs", () => {
	for (const file of [
		"package.json",
		"pnpm-lock.yaml",
		"pnpm-workspace.yaml",
		"patches/zod@4.4.3.patch",
		".oxlintrc.json",
		"scripts/check-affected.ts",
		"server/openapi.yaml",
		"webapp/src/api/core/a.ts",
		"webapp/src/routeTree.gen.ts",
		"webapp/tools/oxlint/index.ts",
		"docs/contributor/erd/schema.mmd",
		".vite-hooks/pre-push",
		"docker/compose.app.yaml",
		"webapp/CLAUDE.md",
		"some-new-root-input.txt",
	]) {
		assert.deepEqual(scopesFor([file]), ["full"], file);
	}
});

await test("a full-gate input overrides scoped inputs", () => {
	assert.deepEqual(scopesFor(["webapp/src/a.tsx", "package.json"]), ["full"]);
	assert.deepEqual(commandsFor(["full"]), [["vp", "run", "quality"]]);
});

await test("discovers committed, staged, unstaged, untracked, deleted, and renamed paths", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "check-affected-"));
	const git = (...args: string[]) =>
		execFileSync("git", args, { cwd: directory, env: environmentForGitFixture() });
	const put = async (file: string, content = file) => {
		await mkdir(path.join(directory, file, ".."), { recursive: true });
		await writeFile(path.join(directory, file), content);
	};
	try {
		git("init", "-b", "main");
		git("config", "user.email", "test@example.com");
		git("config", "user.name", "Test");
		await put("server/renamed.ts");
		await put("docs/deleted.md");
		await put("webapp/unstaged.ts");
		git("add", ".");
		git("commit", "-m", "initial");
		git("checkout", "-q", "-b", "feature");
		await put("server/committed.ts");
		git("add", ".");
		git("commit", "-m", "feature");
		await put("webapp/unstaged.ts", "changed");
		await put("docs/staged.md");
		git("add", "docs/staged.md");
		await put("docker/untracked.txt");
		await rm(path.join(directory, "docs/deleted.md"));
		await mkdir(path.join(directory, "webapp"), { recursive: true });
		git("mv", "server/renamed.ts", "webapp/renamed.ts");

		assert.deepEqual(changedPaths("main", directory), [
			"docker/untracked.txt",
			"docs/deleted.md",
			"docs/staged.md",
			"server/committed.ts",
			"server/renamed.ts",
			"webapp/renamed.ts",
			"webapp/unstaged.ts",
		]);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});
