import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

void test("native state distinguishes missing mirrors and refs from corrupt repositories", () => {
	const root = mkdtempSync(join(tmpdir(), "git-state-"));
	const repository = join(root, "mirror.git");
	const execute = (operation: string, revisions: string[]) =>
		spawnSync(process.execPath, [fileURLToPath(new URL("./operation.ts", import.meta.url))], {
			input: JSON.stringify({ operation, revisions }),
			encoding: "utf8",
			env: { ...process.env, GIT_REPOSITORY_DIRECTORY: repository },
		});
	try {
		const missing = execute("STATUS", []);
		assert.equal(missing.status, 0);
		assert.equal(missing.stdout, "false\n");
		execFileSync("git", ["init", "--bare", "--template=", repository], { stdio: "ignore" });
		const present = execute("STATUS", []);
		assert.equal(present.status, 0);
		assert.equal(present.stdout, "true\n");
		const missingRef = execute("RESOLVE", ["refs/heads/missing"]);
		assert.equal(missingRef.status, 0);
		assert.equal(missingRef.stdout, "");
		const optionShaped = execute("RESOLVE", ["--upload-pack=evil"]);
		assert.equal(optionShaped.status, 0, optionShaped.stderr);
		assert.equal(optionShaped.stdout, "");
		writeFileSync(join(repository, "HEAD"), "invalid repository metadata");
		assert.equal(execute("STATUS", []).status, 1);
		assert.equal(execute("RESOLVE", ["refs/heads/missing"]).status, 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("snapshots reject malformed UTF-8 paths and refs instead of replacing names", () => {
	const root = mkdtempSync(join(tmpdir(), "git-paths-"));
	const repository = join(root, "repository");
	const git = (...args: string[]) =>
		execFileSync("git", ["-C", repository, ...args], {
			encoding: "utf8",
			stdio: ["ignore", "pipe", "ignore"],
		}).trim();
	const snapshot = (head: string) =>
		spawnSync(process.execPath, [fileURLToPath(new URL("./operation.ts", import.meta.url))], {
			input: JSON.stringify({ operation: "SNAPSHOT", revisions: [head] }),
			env: {
				...process.env,
				GIT_REPOSITORY_DIRECTORY: join(repository, ".git"),
				GIT_SNAPSHOT_DIRECTORY: join(root, "snapshot"),
			},
		});
	try {
		execFileSync("git", ["init", "--template=", repository], { stdio: "ignore" });
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
		const invalidPath = Buffer.concat([Buffer.from(`${repository}/invalid-`), Buffer.from([255])]);
		writeFileSync(invalidPath, "source");
		git("add", ".");
		git(
			"-c",
			"user.name=fixture",
			"-c",
			"user.email=fixture@example.invalid",
			"commit",
			"-m",
			"invalid path",
		);
		assert.equal(snapshot(git("rev-parse", "HEAD")).status, 1);
		git("reset", "--hard", base);
		writeFileSync(
			Buffer.concat([Buffer.from(`${repository}/.git/refs/heads/invalid-`), Buffer.from([255])]),
			`${base}\n`,
		);
		assert.equal(snapshot(base).status, 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a repository over the snapshot bound is refused before the snapshot volume is written", () => {
	const root = mkdtempSync(join(tmpdir(), "git-bound-"));
	const repository = join(root, "repository");
	const git = (...args: string[]) =>
		execFileSync("git", ["-C", repository, ...args], {
			encoding: "utf8",
			stdio: ["ignore", "pipe", "ignore"],
		}).trim();
	const snapshot = (bound: number) =>
		spawnSync(process.execPath, [fileURLToPath(new URL("./operation.ts", import.meta.url))], {
			input: JSON.stringify({ operation: "SNAPSHOT", revisions: [git("rev-parse", "HEAD")] }),
			encoding: "utf8",
			env: {
				...process.env,
				GIT_REPOSITORY_DIRECTORY: join(repository, ".git"),
				GIT_SNAPSHOT_DIRECTORY: join(root, "snapshot"),
				GIT_MAX_SNAPSHOT_BYTES: String(bound),
			},
		});
	try {
		execFileSync("git", ["init", "--template=", repository], { stdio: "ignore" });
		writeFileSync(join(repository, "large.txt"), "x".repeat(100_000));
		git("add", ".");
		git(
			"-c",
			"user.name=fixture",
			"-c",
			"user.email=fixture@example.invalid",
			"commit",
			"-m",
			"large",
		);
		git(
			"-c",
			"user.name=fixture",
			"-c",
			"user.email=fixture@example.invalid",
			"tag",
			"-a",
			"-m",
			"blob",
			"blob-tag",
			"HEAD:large.txt",
		);
		const refused = snapshot(50_000);
		assert.equal(refused.status, 1);
		assert.match(refused.stderr, /snapshot bound/);
		assert.equal(spawnSync("test", ["-e", join(root, "snapshot", "large.txt")]).status, 1);
		assert.equal(snapshot(50_000_000).status, 0);
		assert.match(
			readFileSync(join(root, "snapshot", ".git", "hephaestus-captured-refs"), "utf8"),
			new RegExp(`^blob ${git("rev-parse", "HEAD:large.txt")} refs/tags/blob-tag$`, "m"),
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
