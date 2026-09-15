import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, it } from "node:test";

import { globFilesSync } from "./files.ts";
import { findFiles, grep } from "./grep.ts";

const tempDirs: string[] = [];

async function createTempDir(): Promise<string> {
	const dir = await mkdtemp(join(tmpdir(), "grep helper "));
	tempDirs.push(dir);
	return dir;
}

afterEach(async () => {
	await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));
});

void describe("grep", () => {
	void it("treats fixed-string patterns literally without shell interpretation", async () => {
		const dir = await createTempDir();
		const nestedDir = join(dir, "nested dir");
		await mkdir(nestedDir, { recursive: true });
		await writeFile(
			join(nestedDir, "example.ts"),
			"const marker = \"literal $(echo nope) 'quotes'\";\n",
		);

		const matches = await grep("literal $(echo nope) 'quotes'", dir, {
			fixedString: true,
			glob: "**/*.ts",
		});

		assert.equal(matches.length, 1);
		assert.equal(matches[0]?.file, "nested dir/example.ts");
	});

	void it("enforces maxResults globally across files", async () => {
		const dir = await createTempDir();
		await writeFile(join(dir, "one.txt"), "needle\nneedle\n");
		await writeFile(join(dir, "two.txt"), "needle\nneedle\n");
		await writeFile(join(dir, "three.txt"), "needle\nneedle\n");

		const matches = await grep("needle", dir, {
			fixedString: true,
			maxResults: 2,
		});

		assert.equal(matches.length, 2);
		assert.equal(
			matches.every((match) => match.content === "needle"),
			true,
		);
	});

	void it("applies path-aware glob filters instead of basename-only includes", async () => {
		const dir = await createTempDir();
		const nestedDir = join(dir, "src", "nested");
		await mkdir(nestedDir, { recursive: true });
		await writeFile(join(nestedDir, "match.ts"), "needle\n");
		await writeFile(join(nestedDir, "skip.js"), "needle\n");

		const matches = await grep("needle", dir, {
			fixedString: true,
			glob: "src/**/*.ts",
		});

		assert.equal(matches.length, 1);
		assert.equal(matches[0]?.file, "src/nested/match.ts");
	});

	void it("auto-expands basename-only globs to recursive matching", async () => {
		const dir = await createTempDir();
		await mkdir(join(dir, "src", "Views"), { recursive: true });
		await writeFile(join(dir, "src", "Views", "ContentView.swift"), 'print("hello")\n');
		await writeFile(join(dir, "RootFile.swift"), 'print("root")\n');

		const matches = await grep("print", dir, {
			fixedString: true,
			glob: "*.swift",
		});

		assert.equal(matches.length, 2);
		const files = matches.map((m) => m.file).toSorted();
		assert.ok(files.includes("RootFile.swift"));
		assert.ok(files.includes("src/Views/ContentView.swift"));
	});

	void it("finds source files regardless of hidden or dependency-shaped paths", async () => {
		const dir = await createTempDir();
		await mkdir(join(dir, "src", "nested"), { recursive: true });
		await mkdir(join(dir, ".hidden"), { recursive: true });
		await mkdir(join(dir, "node_modules", "pkg"), { recursive: true });
		await mkdir(join(dir, ".build"), { recursive: true });

		await writeFile(join(dir, "src", "nested", "match.swift"), "struct Match {}\n");
		await writeFile(join(dir, ".hidden", "hidden.swift"), "struct Hidden {}\n");
		await writeFile(join(dir, "node_modules", "pkg", "dep.swift"), "struct Dep {}\n");
		await writeFile(join(dir, ".build", "generated.swift"), "struct Generated {}\n");

		const files = findFiles(dir, "swift");

		assert.deepEqual(files, [
			join(dir, ".build", "generated.swift"),
			join(dir, ".hidden", "hidden.swift"),
			join(dir, "node_modules", "pkg", "dep.swift"),
			join(dir, "src", "nested", "match.swift"),
		]);
	});

	void it("does not discover source through symlinked files or directories", async () => {
		const dir = await createTempDir();
		const outside = await createTempDir();
		await writeFile(join(outside, "outside.swift"), "struct Outside {}\n");
		await writeFile(join(dir, "inside.swift"), "struct Inside {}\n");
		await symlink(outside, join(dir, "linked"), "dir");
		await symlink(join(outside, "outside.swift"), join(dir, "linked.swift"));

		assert.deepEqual(findFiles(dir, "swift"), [join(dir, "inside.swift")]);
	});

	void it("searches hidden source with standard brace and globstar patterns", async () => {
		const dir = await createTempDir();
		await mkdir(join(dir, ".github", "nested"), { recursive: true });
		await writeFile(join(dir, ".github", "nested", "check.ts"), "needle\n");
		await writeFile(join(dir, ".config.js"), "needle\n");
		await writeFile(join(dir, "skip.txt"), "needle\n");

		const matches = await grep("needle", dir, { glob: "**/*.{ts,js}" });

		assert.deepEqual(matches.map((match) => match.file).toSorted(), [
			".config.js",
			".github/nested/check.ts",
		]);
	});

	void it("never returns a directory whose name matches the file pattern", async () => {
		const dir = await createTempDir();
		await mkdir(join(dir, "directory.ts"));
		await writeFile(join(dir, "file.ts"), "export {};\n");

		assert.deepEqual(globFilesSync("*.ts", dir), ["file.ts"]);
	});
});
