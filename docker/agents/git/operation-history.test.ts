import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtemp, rm, writeFile, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

await test("historical reads use witnessed roots and Git file bytes, not injected refs", async () => {
	const directory = await mkdtemp(join(tmpdir(), "git-history-"));
	try {
		const git = (...args: string[]) => execFileSync("git", ["-C", directory, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
		git("init", "--initial-branch=main");
		git("config", "user.name", "Test"); git("config", "user.email", "test@example.com"); git("config", "commit.gpgsign", "false");
		await writeFile(join(directory, "source.txt"), "initial\n"); git("add", "."); git("commit", "-m", "Initial");
		git("checkout", "-b", "feature");
		await writeFile(join(directory, "source.txt"), "feature\n"); git("commit", "-am", "Feature");
		const feature = git("rev-parse", "HEAD");
		git("checkout", "main");
		await writeFile(join(directory, "source.txt"), "main\n");
		await symlink("source.txt", join(directory, "link")); git("add", "."); git("commit", "-m", "Main");
		const head = git("rev-parse", "HEAD");
		git("update-ref", "refs/remotes/origin/feature", feature);
		await writeFile(join(directory, ".git/hephaestus-captured-refs"), `${feature} refs/remotes/origin/feature\n`);
		git("update-ref", "-d", "refs/remotes/origin/feature");
		const injected = git("commit-tree", git("rev-parse", "HEAD^{tree}"), "-m", "Uncaptured root");
		git("update-ref", "refs/remotes/origin/injected", injected);
		const run = (revision: string, path: string, pinned = head) => spawnSync(process.execPath, [fileURLToPath(new URL("./operation.ts", import.meta.url))], {
			input: JSON.stringify({ operation: "HISTORICAL_BLOB", revisions: [revision, pinned, path] }), encoding: "utf8", timeout: 10_000,
			env: { ...process.env, GIT_REPOSITORY_DIRECTORY: join(directory, ".git"), GIT_TEMP_DIRECTORY: directory },
		});
        const batch = spawnSync(process.execPath, [fileURLToPath(new URL("./operation.ts", import.meta.url))], {
            input: JSON.stringify({ operation: "CITED_BLOBS", revisions: [head, head, "source.txt", feature, "source.txt"] }), timeout: 10_000,
            env: { ...process.env, GIT_REPOSITORY_DIRECTORY: join(directory, ".git"), GIT_TEMP_DIRECTORY: directory },
        });
        assert.equal(batch.status, 0, batch.stderr.toString());
        assert.equal(execFileSync("tar", ["-tf", "-"], {input: batch.stdout, encoding: "utf8"}), "0\n1\n");
        assert.equal(execFileSync("tar", ["-xOf", "-", "0"], {input: batch.stdout, encoding: "utf8"}), "main\n");
        assert.equal(execFileSync("tar", ["-xOf", "-", "1"], {input: batch.stdout, encoding: "utf8"}), "feature\n");
		assert.equal(run(head, "source.txt").stdout, "main\n");
		assert.equal(run(feature, "source.txt").stdout, "feature\n");
		assert.notEqual(run(injected, "source.txt").status, 0);
		assert.equal(run(head, "link").stdout, "source.txt");
		assert.notEqual(run(feature, "source.txt", feature).status, 0);
	} finally { await rm(directory, { recursive: true, force: true }); }
});
