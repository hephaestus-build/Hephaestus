import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { isPracticeModule } from "../../../../../../docker/agents/precompute/lib/practice-contract.ts";
import type { DiffFile } from "../../../../../../docker/agents/precompute/lib/types.ts";

const repositoryRoot = fileURLToPath(new URL("../../../../../../", import.meta.url));

async function stage() {
	const root = mkdtempSync(path.join(tmpdir(), "dependency-precompute-"));
	mkdirSync(path.join(root, "practices"));
	mkdirSync(path.join(root, "repo"), { recursive: true });
	writeFileSync(path.join(root, "package.json"), '{"type":"module"}\n');
	symlinkSync(path.join(repositoryRoot, "docker/agents/precompute/lib"), path.join(root, "lib"));
	const staged = path.join(root, "practices/changes-dependencies-deliberately.ts");
	cpSync(
		path.join(
			repositoryRoot,
			"server/application/src/main/resources/practices/precompute/changes-dependencies-deliberately.ts",
		),
		staged,
	);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) {
		throw new Error("script does not export a default function");
	}
	return { root, script: mod.default };
}

const metadata = {
	pr_number: 1,
	pr_url: "https://example.org/team/project/pull/1",
	repository_full_name: "team/project",
	source_branch: "f",
	target_branch: "main",
	commit_sha: "abc123",
};

function diffFile(file: string, added: string[], removed: string[] = []): DiffFile {
	return {
		path: file,
		addedLines: new Map(added.map((line, i) => [10 + i, line])),
		removedLines: new Map(removed.map((line, i) => [10 + i, line])),
		hunks: [],
	};
}

void test("an XcodeGen project.yml is a dependency manifest: a package's url and bound are one fact", async () => {
	const { root, script } = await stage();
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				[
					"project.yml",
					diffFile(
						"project.yml",
						[
							"  ConfettiSwiftUI:",
							"    url: https://github.com/simibac/ConfettiSwiftUI",
							"    from: 2.0.0",
							"      - package: ConfettiSwiftUI",
						],
						["    from: 1.3.0"],
					),
				],
			]),
			metadata,
		);
		const added = result.hints.filter((h) => h.pattern === "dep:ADDED");
		assert.deepEqual(
			added.map((h) => [h.flags.dependency, h.context]),
			[["ConfettiSwiftUI", "+ ConfettiSwiftUI from:2.0.0"]],
		);
		assert.equal(result.metrics.manifestsChanged, 1);
		assert.equal(result.metrics.depsAdded, 1);
		// A bound line with no url line before it in the window names no package and is not a fact.
		assert.equal(result.metrics.depsRemoved, 0);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
