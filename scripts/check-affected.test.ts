import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
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

await test("selects the Chrome extension, and the webapp inputs it imports", () => {
	assert.deepEqual(scopesFor(["extension/src/entrypoints/background.ts"]), ["extension"]);
	assert.deepEqual(scopesFor(["extension/wxt.config.ts", "extension/e2e/seed.sql"]), ["extension"]);
	assert.deepEqual(scopesFor(["webapp/src/components/practice-vocabulary/outcome-defs.ts"]), [
		"extension",
		"webapp",
	]);
	for (const file of [
		"webapp/brand/hephaestus-mark.svg",
		"webapp/src/components/icons/brand.tsx",
		"webapp/src/lib/artifact-kind-slugs.ts",
		"webapp/src/lib/artifact-kinds.ts",
		"webapp/src/components/common/status-def.ts",
		"webapp/src/components/common/FacetMultiSelect.tsx",
		"webapp/src/lib/sign-in-providers.ts",
		"webapp/src/styles.css",
	]) {
		assert.deepEqual(scopesFor([file]), ["extension", "webapp"], file);
	}
	for (const file of [
		"webapp/src/components/ui/button.tsx",
		"webapp/src/components/practice-trace/PracticeTraceTable.tsx",
		"webapp/src/lib/utils.ts",
	]) {
		assert.deepEqual(scopesFor([file]), ["webapp"], file);
	}
	assert.deepEqual(scopesFor(["webapp/src/styles/theme-tokens.css"]), [
		"docs",
		"extension",
		"webapp",
	]);
	// The lint plugin both trees load, and the spec the extension's client comes from, run everything.
	assert.deepEqual(scopesFor(["webapp/tools/oxlint/rules/a.ts"]), ["full"]);
	assert.deepEqual(scopesFor(["server/openapi.yaml"]), ["full"]);
});

/** A path-filter glob as a pattern; the filter uses only `**` and literal dots. */
const asPattern = (glob: string) =>
	new RegExp(`^${glob.replaceAll(".", String.raw`\.`).replaceAll("**", ".*")}$`, "u");

await test("the CI extension filters select every webapp input the extension imports", async () => {
	const workflow = await readFile(".github/workflows/cicd.yml", "utf8");
	const inputs = [
		"webapp/src/components/practice-vocabulary/trace-outcome-defs.ts",
		"webapp/brand/hephaestus-mark.svg",
		"webapp/src/components/icons/brand.tsx",
		"webapp/src/lib/artifact-kind-slugs.ts",
		"webapp/src/lib/artifact-kinds.ts",
		"webapp/src/components/common/status-def.ts",
		"webapp/src/components/common/FacetMultiSelect.tsx",
		"webapp/src/lib/sign-in-providers.ts",
		"webapp/src/styles/theme-tokens.css",
		"webapp/src/styles.css",
		"webapp/tools/oxlint/rules/a.ts",
	];
	for (const name of ["extension", "extension-e2e"]) {
		const filter = new RegExp(`^ {12}${name}:\\n(?<entries>(?: {14}- '[^']+'\\n)+)`, "mu").exec(
			workflow,
		)?.groups?.entries;
		assert.ok(filter !== undefined, `cicd.yml has an ${name} filter`);
		const globs = [...filter.matchAll(/- '(?<glob>[^']+)'/gu)].map(
			({ groups }) => groups?.glob ?? "",
		);
		const webappGlobs = globs.filter(
			(glob) => glob.startsWith("webapp/") && !glob.startsWith("webapp/e2e/"),
		);
		for (const file of inputs) {
			// Browser behavior is unaffected by the lint plugin; the extension's quality leg owns it.
			if (name === "extension-e2e" && file.startsWith("webapp/tools/oxlint/")) {
				continue;
			}
			assert.ok(
				webappGlobs.some((glob) => asPattern(glob).test(file)),
				`the ${name} filter must select ${file}`,
			);
		}
		for (const glob of webappGlobs.filter((entry) => !entry.startsWith("webapp/tools/oxlint/"))) {
			const sample = glob.replace("**", "a.ts");
			assert.ok(
				scopesFor([sample]).includes("extension"),
				`check-affected must map ${glob} to the extension scope`,
			);
		}
	}
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
	assert.deepEqual(commandsFor(["agents", "docs", "extension", "server", "webapp"]), [
		["vp", "run", "affected:agents"],
		["vp", "run", "affected:docs"],
		["vp", "run", "affected:extension"],
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
		"extension/src/api/sdk.gen.ts",
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
	assert.deepEqual(scopesFor(["extension/src/a.tsx", "server/openapi.yaml"]), ["full"]);
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
