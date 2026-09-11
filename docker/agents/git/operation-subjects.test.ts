import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

await test("subject scanning reaches references beyond five hundred commits without file details", async () => {
	const directory = await mkdtemp(join(tmpdir(), "git-subjects-"));
	try {
		const git = (...args: string[]) => execFileSync("git", ["-C", directory, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
		git("init", "--initial-branch=main");
		let history = "blob\nmark :1\ndata 1\nx\n";
		for (let index = 0; index <= 600; index++) {
			const message = `Reference #${index}`;
			history += `commit refs/heads/main\nmark :${index + 2}\ncommitter Test <test@example.com> ${1700000000 + index} +0000\ndata ${Buffer.byteLength(message)}\n${message}\n`;
			if (index > 0) history += `from :${index + 1}\n`;
			history += "M 100644 :1 source.txt\n\n";
		}
		execFileSync("git", ["-C", directory, "fast-import", "--quiet"], { input: history, stdio: ["pipe", "ignore", "pipe"] });
		const result = spawnSync(process.execPath, [fileURLToPath(new URL("./operation.ts", import.meta.url))], {
			input: JSON.stringify({ operation: "COMMIT_SUBJECTS", revisions: [git("rev-list", "--max-parents=0", "HEAD"), git("rev-parse", "HEAD")] }),
			encoding: "utf8", timeout: 10_000, env: { ...process.env, GIT_REPOSITORY_DIRECTORY: join(directory, ".git") },
		});
		assert.equal(result.status, 0, result.stderr);
		const subjects = result.stdout.split("\0");
		assert.equal(subjects.pop(), "");
		assert.equal(subjects.length, 600);
		assert.ok(subjects.includes("Reference #1"));
		assert.ok(subjects.includes("Reference #600"));
        const commits = spawnSync(process.execPath, [fileURLToPath(new URL("./operation.ts", import.meta.url))], {
            input: JSON.stringify({ operation: "REVIEW_COMMITS", revisions: [git("rev-list", "--max-parents=0", "HEAD"), git("rev-parse", "HEAD")] }),
            encoding: "utf8", timeout: 30_000, env: { ...process.env, GIT_REPOSITORY_DIRECTORY: join(directory, ".git") },
        });
        assert.equal(commits.status, 0, commits.stderr);
        assert.equal((commits.stdout.match(/"sha":/g) ?? []).length, 600);
        assert.ok(commits.stdout.startsWith('{\n"commits": [\n{\n  "sha":'));
        assert.ok(commits.stdout.endsWith('\n],\n"truncated": false\n}\n'));
        assert.ok(commits.stdout.indexOf('"subject": "Reference #1"') < commits.stdout.indexOf('"subject": "Reference #600"'));

	} finally { await rm(directory, { recursive: true, force: true }); }
});
