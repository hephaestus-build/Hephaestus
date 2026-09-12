import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable } from "node:stream";
import test from "node:test";
import { addedSecretVerdicts, blobSizes, scanSecrets } from "./secret-scan.ts";

function finding(start: number, end = start) {
	return { File: "config.txt", StartLine: start, EndLine: end, RuleID: "test-rule" };
}

void test("secret verdicts cover final added lines, not removed or unchanged secrets", async () => {
	const diff = Buffer.from(
		"--- a/config.txt\n+++ b/config.txt\n@@ -2,1 +2,1 @@\n-old-secret\n+new-secret\n",
	);
	const result = await addedSecretVerdicts(
		"config.txt",
		[finding(1), finding(2), finding(3)],
		Readable.from([diff]),
	);
	assert.deepEqual(result, [
		{
			path: "config.txt",
			line: 2,
			ruleId: "test-rule",
			lineHash: createHash("sha256").update("new-secret").digest("hex"),
		},
	]);
	assert.ok(!JSON.stringify(result).includes("new-secret"));
});

void test("secret line identities preserve whitespace and CR bytes across stream boundaries", async () => {
	const line = " \tsecret-value  \r";
	const diff = Buffer.from(`@@ -0,0 +1,1 @@\n+${line}\n\\ No newline at end of file\n`);
	const chunks = Array.from(diff, (byte) => Buffer.from([byte]));
	const result = await addedSecretVerdicts("config.txt", [finding(1)], Readable.from(chunks));
	assert.equal(result[0]?.lineHash, createHash("sha256").update(line).digest("hex"));
});

void test("multiline scanner matches retain only added lines in each hunk", async () => {
	const diff = Buffer.from("@@ -2,1 +2,1 @@\n-removed\n+first\n@@ -6,1 +6,1 @@\n-another\n+last\n");
	const result = await addedSecretVerdicts("config.txt", [finding(1, 6)], Readable.from([diff]));
	assert.deepEqual(
		result.map((verdict) => verdict.line),
		[2, 6],
	);
});

void test("a scanner match outside final additions does not become an observation", async () => {
	const result = await addedSecretVerdicts(
		"config.txt",
		[finding(10)],
		Readable.from([Buffer.from("@@ -1,1 +1,1 @@\n-old\n+new")]),
	);
	assert.deepEqual(result, []);
});

void test("a long added source line is hashed incrementally across bounded chunks", async () => {
	const chunk = Buffer.alloc(64 * 1024, 120);
	const expected = createHash("sha256");
	function* diff() {
		yield Buffer.from("@@ -0,0 +1,1 @@\n+");
		for (let index = 0; index < 640; index++) {
			expected.update(chunk);
			yield chunk;
		}
		yield Buffer.from("\n");
	}
	const result = await addedSecretVerdicts("config.txt", [finding(1)], Readable.from(diff()));
	assert.equal(result[0]?.lineHash, expected.digest("hex"));
});

void test("blob sizes come from one tree listing and name every changed path", async () => {
	const directory = await mkdtemp(join(tmpdir(), "secret-sizes-"));
	try {
		const git = (...args: string[]) =>
			execFileSync("git", ["-C", directory, ...args], {
				encoding: "utf8",
				stdio: ["ignore", "pipe", "ignore"],
			}).trim();
		git("init", "--initial-branch=main");
		await mkdir(join(directory, "nested"));
		await writeFile(join(directory, "small.txt"), "x".repeat(10));
		await writeFile(join(directory, "nested", "large.txt"), "y".repeat(5000));
		git("add", ".");
		git(
			"-c",
			"user.name=fixture",
			"-c",
			"user.email=fixture@example.invalid",
			"commit",
			"-m",
			"sizes",
		);
		const head = git("rev-parse", "HEAD");
		const start = (args: string[]) =>
			spawn("git", ["--git-dir", join(directory, ".git"), ...args], {
				stdio: ["ignore", "pipe", "inherit"],
			});
		const sizes = await blobSizes(start, head, new Set(["small.txt", "nested/large.txt"]));
		assert.deepEqual(
			[...sizes],
			[
				["nested/large.txt", 5000],
				["small.txt", 10],
			],
		);
		await assert.rejects(
			blobSizes(start, head, new Set(["small.txt", "absent.txt"])),
			/not in the tree/,
		);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

void test("a changed blob over the ceiling is skipped by name and never materialised", async () => {
	const directory = await mkdtemp(join(tmpdir(), "secret-ceiling-"));
	try {
		const git = (...args: string[]) =>
			execFileSync("git", ["-C", directory, ...args], {
				encoding: "utf8",
				stdio: ["ignore", "pipe", "ignore"],
			}).trim();
		git("init", "--initial-branch=main");
		git(
			"-c",
			"user.name=fixture",
			"-c",
			"user.email=fixture@example.invalid",
			"commit",
			"--allow-empty",
			"-m",
			"base",
		);
		const base = git("rev-parse", "HEAD");
		await writeFile(join(directory, "small.txt"), "token\n");
		await writeFile(join(directory, "large.txt"), "z".repeat(200));
		git("add", ".");
		git(
			"-c",
			"user.name=fixture",
			"-c",
			"user.email=fixture@example.invalid",
			"commit",
			"-m",
			"change",
		);
		const head = git("rev-parse", "HEAD");
		// A stand-in scanner: reports nothing and lists what it was given to scan.
		const shim = join(directory, "shim");
		await mkdir(shim);
		await writeFile(
			join(shim, "gitleaks"),
			'#!/bin/sh\nfind "$2" -type f | sort > "$(dirname "$0")/listing"\nwhile [ $# -gt 0 ]; do [ "$1" = "--report-path" ] && printf "[]" > "$2"; shift; done\n',
			{ mode: 0o755 },
		);
		const path = process.env.PATH;
		process.env.PATH = `${shim}:${path}`;
		try {
			const start = (args: string[]) =>
				spawn("git", ["--git-dir", join(directory, ".git"), ...args], {
					stdio: ["ignore", "pipe", "inherit"],
				});
			process.env.GIT_TEMP_DIRECTORY = directory;
			const result = await scanSecrets([base, head], start, 100);
			assert.deepEqual(result, { verdicts: [], skipped: ["large.txt"] });
			assert.match(await readFile(join(shim, "listing"), "utf8"), /^\S+\/sources\/small\.txt\n$/);
		} finally {
			process.env.PATH = path;
			delete process.env.GIT_TEMP_DIRECTORY;
		}
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});
