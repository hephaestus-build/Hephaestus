import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

void test("commit context counts changed paths per commit and leaves merges uncounted", async () => {
	const directory = await mkdtemp(join(tmpdir(), "git-commits-"));
	try {
		const git = (...args: string[]) =>
			execFileSync("git", ["-C", directory, ...args], {
				encoding: "utf8",
				stdio: ["ignore", "pipe", "ignore"],
			}).trim();
		const commit = (message: string) =>
			git(
				"-c",
				"user.name=fixture",
				"-c",
				"user.email=fixture@example.invalid",
				"commit",
				"--allow-empty",
				"-m",
				message,
			);
		git("init", "--initial-branch=main");
		commit("base");
		const base = git("rev-parse", "HEAD");
		await writeFile(join(directory, "one.txt"), "one\n");
		await writeFile(join(directory, "two.txt"), "two\n");
		git("add", ".");
		commit("two files\n\nA body\nwith lines\n");
		git("checkout", "-q", "-b", "side");
		await writeFile(join(directory, "side.txt"), "side\n");
		git("add", ".");
		commit("side\x01subject");
		git("checkout", "-q", "main");
		commit("nothing changed");
		git(
			"-c",
			"user.name=fixture",
			"-c",
			"user.email=fixture@example.invalid",
			"merge",
			"--no-ff",
			"-m",
			"merge side",
			"side",
		);
		const result = spawnSync(
			process.execPath,
			[fileURLToPath(new URL("./operation.ts", import.meta.url))],
			{
				input: JSON.stringify({
					operation: "REVIEW_COMMITS",
					revisions: [base, git("rev-parse", "HEAD")],
				}),
				encoding: "utf8",
				timeout: 10_000,
				env: { ...process.env, GIT_REPOSITORY_DIRECTORY: join(directory, ".git") },
			},
		);
		assert.equal(result.status, 0, result.stderr);
		const parsed: unknown = JSON.parse(result.stdout);
		assert.ok(typeof parsed === "object" && parsed !== null && "commits" in parsed);
		assert.ok(Array.isArray(parsed.commits));
		const commits = parsed.commits.map((entry: unknown) => {
			assert.ok(isRecord(entry));
			const { subject, body, parent_count, changed_files } = entry;
			return { subject, body, parent_count, changed_files };
		});
		assert.deepEqual(commits[0], {
			subject: "two files",
			body: "A body\nwith lines",
			parent_count: 1,
			changed_files: 2,
		});
		assert.deepEqual(
			commits.slice(1, 3).toSorted((a, b) => String(a.subject).localeCompare(String(b.subject))),
			[
				{ subject: "nothing changed", body: undefined, parent_count: 1, changed_files: 0 },
				{ subject: "side\x01subject", body: undefined, parent_count: 1, changed_files: 1 },
			],
		);
		assert.deepEqual(commits[3], {
			subject: "merge side",
			body: undefined,
			parent_count: 2,
			changed_files: undefined,
		});
		assert.equal(commits.length, 4);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});
