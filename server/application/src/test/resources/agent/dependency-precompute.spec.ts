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

void test("an XcodeGen project.yml edit lists its changed package lines without calling the package new", async () => {
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
		// The url may be unchanged and merely re-added beside the new bound: nothing here says the package is new.
		assert.deepEqual(
			result.hints.map((h) => [h.pattern, h.context, h.flags.side]),
			[
				["candidate:raw manifest line", "- from: 1.3.0", "removed"],
				[
					"candidate:raw manifest line",
					"+ url: https://github.com/simibac/ConfettiSwiftUI",
					"added",
				],
				["candidate:raw manifest line", "+ from: 2.0.0", "added"],
			],
		);
		assert.equal(result.metrics.manifestsChanged, 1);
		assert.equal(result.metrics.onlyAdded, 0);
		assert.equal(result.metrics.unpairedManifestLines, 3);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a pom.xml version moved away from its artifactId is not a dropped pin; a requirement that loses its pin is", async () => {
	const { root, script } = await stage();
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				[
					"pom.xml",
					diffFile(
						"pom.xml",
						[
							"      <artifactId>guava</artifactId>",
							"      <!-- the last release for Java 8 -->",
							"      <type>jar</type>",
							"      <version>33.0.0-jre</version>",
						],
						["      <artifactId>guava</artifactId>", "      <version>33.0.0-jre</version>"],
					),
				],
				[
					"requirements.txt",
					diffFile("requirements.txt", ["requests"], ["requests==2.31.0", "urllib3==2.2.1"]),
				],
			]),
			metadata,
		);
		assert.equal(result.metrics.unpairedManifestLines, 4);
		assert.equal(result.metrics.pinsDropped, 1);
		assert.deepEqual(
			result.hints.filter((h) => h.file === "requirements.txt").map((h) => [h.pattern, h.context]),
			[
				["candidate:PIN_DROPPED", "requests: ==2.31.0 -> "],
				["candidate:ONLY_REMOVED", "- urllib3 ==2.2.1"],
			],
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("metadata lines shaped like dependencies stay candidates with their constraint delta", async () => {
	const { root, script } = await stage();
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				["package.json", diffFile("package.json", ['    "node": ">=22"'], ['    "node": ">=20"'])],
				[
					"Cargo.toml",
					diffFile("Cargo.toml", ['rust-version = "1.80"'], ['rust-version = "1.78"']),
				],
			]),
			metadata,
		);
		// An engines entry and a toolchain floor: the delta is real, the dependency identity is not established.
		assert.deepEqual(
			result.hints.map((h) => [h.file, h.pattern, h.context]),
			[
				["package.json", "candidate:BUMPED", "node: >=20 -> >=22"],
				["Cargo.toml", "candidate:BUMPED", "rust-version: 1.78 -> 1.80"],
			],
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a lockfile elsewhere in the checkout is reported by its path, not as the changed manifest's", async () => {
	const { root, script } = await stage();
	try {
		mkdirSync(path.join(root, "repo/tools/docs"), { recursive: true });
		writeFileSync(path.join(root, "repo/tools/docs/package-lock.json"), "{}\n");
		const result = await script(
			path.join(root, "repo"),
			new Map([["app/package.json", diffFile("app/package.json", ['    "left-pad": "^1.3.0",'])]]),
			metadata,
		);
		assert.match(
			result.directions.join("\n"),
			/in the checkout: tools\/docs\/package-lock\.json; touched in this diff: none/u,
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
