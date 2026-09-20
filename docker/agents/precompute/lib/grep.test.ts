import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, describe, it } from "node:test";

import { globFilesSync } from "./files.ts";
import { findFiles, grep } from "./grep.ts";

const tempDirs: string[] = [];

async function createTempDir(): Promise<string> {
	const dir = await mkdtemp(path.join(tmpdir(), "grep helper "));
	tempDirs.push(dir);
	return dir;
}

afterEach(async () => {
	await Promise.all(
		tempDirs.splice(0).map(async (dir) => rm(dir, { recursive: true, force: true })),
	);
});

void describe("grep", () => {
	void it("treats fixed-string patterns literally without shell interpretation", async () => {
		const dir = await createTempDir();
		const nestedDir = path.join(dir, "nested dir");
		await mkdir(nestedDir, { recursive: true });
		await writeFile(
			path.join(nestedDir, "example.ts"),
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
		await writeFile(path.join(dir, "one.txt"), "needle\nneedle\n");
		await writeFile(path.join(dir, "two.txt"), "needle\nneedle\n");
		await writeFile(path.join(dir, "three.txt"), "needle\nneedle\n");

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
		const nestedDir = path.join(dir, "src", "nested");
		await mkdir(nestedDir, { recursive: true });
		await writeFile(path.join(nestedDir, "match.ts"), "needle\n");
		await writeFile(path.join(nestedDir, "skip.js"), "needle\n");

		const matches = await grep("needle", dir, {
			fixedString: true,
			glob: "src/**/*.ts",
		});

		assert.equal(matches.length, 1);
		assert.equal(matches[0]?.file, "src/nested/match.ts");
	});

	void it("auto-expands basename-only globs to recursive matching", async () => {
		const dir = await createTempDir();
		await mkdir(path.join(dir, "src", "Views"), { recursive: true });
		await writeFile(path.join(dir, "src", "Views", "ContentView.swift"), 'print("hello")\n');
		await writeFile(path.join(dir, "RootFile.swift"), 'print("root")\n');

		const matches = await grep("print", dir, {
			fixedString: true,
			glob: "*.swift",
		});

		assert.equal(matches.length, 2);
		const files = new Set(matches.map((m) => m.file).toSorted());
		assert.ok(files.has("RootFile.swift"));
		assert.ok(files.has("src/Views/ContentView.swift"));
	});

	void it("finds extension matches without shelling out and skips ignored paths", async () => {
		const dir = await createTempDir();
		await mkdir(path.join(dir, "src", "nested"), { recursive: true });
		await mkdir(path.join(dir, ".hidden"), { recursive: true });
		await mkdir(path.join(dir, "node_modules", "pkg"), { recursive: true });
		await mkdir(path.join(dir, ".build"), { recursive: true });

		await writeFile(path.join(dir, "src", "nested", "match.swift"), "struct Match {}\n");
		await writeFile(path.join(dir, ".hidden", "hidden.swift"), "struct Hidden {}\n");
		await writeFile(path.join(dir, "node_modules", "pkg", "dep.swift"), "struct Dep {}\n");
		await writeFile(path.join(dir, ".build", "generated.swift"), "struct Generated {}\n");

		const files = findFiles(dir, "swift");

		assert.equal(files.length, 1);
		assert.equal(files[0], path.join(dir, "src", "nested", "match.swift"));
	});

	void it("never returns a directory whose name matches the file pattern", async () => {
		const dir = await createTempDir();
		await mkdir(path.join(dir, "directory.ts"));
		await writeFile(path.join(dir, "file.ts"), "export {};\n");

		assert.deepEqual(globFilesSync("*.ts", dir), ["file.ts"]);
	});
});
