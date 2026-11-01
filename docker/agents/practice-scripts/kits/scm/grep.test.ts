import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, describe, it } from "node:test";

import { globFilesSync } from "./files.ts";
import { grep } from "./grep.ts";

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
	void it("cancels a running search and settles its child process", async () => {
		const dir = await createTempDir();
		await writeFile(path.join(dir, "large.txt"), "a line with no match\n".repeat(10_000));
		const cancelled = new AbortController();
		const searching = grep("needle", dir, {}, cancelled.signal);
		cancelled.abort();
		await assert.rejects(searching, { name: "AbortError" });
	});
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

	void it("finds source files regardless of hidden or dependency-shaped paths", async () => {
		const dir = await createTempDir();
		await mkdir(path.join(dir, "src", "nested"), { recursive: true });
		await mkdir(path.join(dir, ".hidden"), { recursive: true });
		await mkdir(path.join(dir, "node_modules", "pkg"), { recursive: true });
		await mkdir(path.join(dir, ".build"), { recursive: true });

		await writeFile(path.join(dir, "src", "nested", "match.swift"), "struct Match {}\n");
		await writeFile(path.join(dir, ".hidden", "hidden.swift"), "struct Hidden {}\n");
		await writeFile(path.join(dir, "node_modules", "pkg", "dep.swift"), "struct Dep {}\n");
		await writeFile(path.join(dir, ".build", "generated.swift"), "struct Generated {}\n");

		const matches = await grep("struct", dir, { glob: "*.swift" });
		const files = matches.map((match) => match.file).toSorted();

		assert.deepEqual(files, [
			".build/generated.swift",
			".hidden/hidden.swift",
			"node_modules/pkg/dep.swift",
			"src/nested/match.swift",
		]);
	});

	void it("does not discover source through symlinked files or directories", async () => {
		const dir = await createTempDir();
		const outside = await createTempDir();
		await writeFile(path.join(outside, "outside.swift"), "struct Outside {}\n");
		await writeFile(path.join(dir, "inside.swift"), "struct Inside {}\n");
		await symlink(outside, path.join(dir, "linked"), "dir");
		await symlink(path.join(outside, "outside.swift"), path.join(dir, "linked.swift"));

		const matches = await grep("struct", dir, { glob: "*.swift" });
		assert.deepEqual(
			matches.map((match) => match.file),
			["inside.swift"],
		);
	});

	void it("searches hidden source with standard brace and globstar patterns", async () => {
		const dir = await createTempDir();
		await mkdir(path.join(dir, ".github", "nested"), { recursive: true });
		await writeFile(path.join(dir, ".github", "nested", "check.ts"), "needle\n");
		await writeFile(path.join(dir, ".config.js"), "needle\n");
		await writeFile(path.join(dir, "skip.txt"), "needle\n");

		const matches = await grep("needle", dir, { glob: "**/*.{ts,js}" });

		assert.deepEqual(matches.map((match) => match.file).toSorted(), [
			".config.js",
			".github/nested/check.ts",
		]);
	});

	void it("never returns a directory whose name matches the file pattern", async () => {
		const dir = await createTempDir();
		await mkdir(path.join(dir, "directory.ts"));
		await writeFile(path.join(dir, "file.ts"), "export {};\n");

		assert.deepEqual(globFilesSync("*.ts", dir), ["file.ts"]);
	});

	void it("finds every file under a pattern with more than one ** between sections", async () => {
		const dir = await createTempDir();
		for (const file of [
			"web/src/components/Card.tsx",
			"web/components/admin/forms/Field.tsx",
			"web/src/App.tsx",
		]) {
			await mkdir(path.join(dir, path.dirname(file)), { recursive: true });
			await writeFile(path.join(dir, file), "export {};\n");
		}

		assert.deepEqual(globFilesSync("web/**/components/**/*.tsx", dir), [
			"web/components/admin/forms/Field.tsx",
			"web/src/components/Card.tsx",
		]);
	});

	void it("reads nothing for an empty or comment pattern", () => {
		const missing = path.join(tmpdir(), "glob-files-sync-no-such-directory");

		assert.deepEqual(globFilesSync("", missing), []);
		assert.deepEqual(globFilesSync("#comment", missing), []);
	});

	void it("splits a file name that holds :<n>: and skips binary files", async () => {
		const dir = await createTempDir();
		await writeFile(path.join(dir, "a:12:b.ts"), "first\nneedle: here\n");
		await writeFile(path.join(dir, "image.bin"), Buffer.from("needle\0\u0001\u0002"));

		for (const options of [{}, { glob: "*" }]) {
			assert.deepEqual(await grep("needle", dir, options), [
				{ file: "a:12:b.ts", line: 2, content: "needle: here" },
			]);
		}
	});

	void it("never reads the checkout's .git folder through a glob", async () => {
		const dir = await createTempDir();
		await mkdir(path.join(dir, ".git"));
		await writeFile(path.join(dir, ".git", "config"), "url = https://token@example.org/x\n");
		await writeFile(path.join(dir, "notes.md"), "token@ here\n");

		for (const glob of ["**", "**/*", ".git/*", "**/config"]) {
			const matches = await grep("token@", dir, { glob, fixedString: true });
			assert.ok(!matches.some((match) => match.file.startsWith(".git")), glob);
		}
		assert.deepEqual(globFilesSync("**", dir), ["notes.md"]);
	});
});
