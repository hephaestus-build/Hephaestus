import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { environmentForGitFixture } from "./git-environment.ts";

const entry = (id: string) => ({
	id,
	owner: "maintainers",
	reason: "Existing gap",
	removeWhen: "Add its check",
});

void test("the baseline comparison uses committed base contents and fails closed without a base", () => {
	const root = mkdtempSync(path.join(tmpdir(), "debt-ratchet-"));
	const env = environmentForGitFixture({ PR_BASE_SHA: "refs/heads/base" });
	const git = (...args: string[]) => execFileSync("git", args, { cwd: root, env, stdio: "pipe" });

	const baseline = (ids: string[]) =>
		writeFileSync(path.join(root, "baseline.json"), JSON.stringify(ids.map(entry)));
	const run = (ids: string[], base = "refs/heads/base") =>
		spawnSync(
			process.execPath,
			[
				"--input-type=module",
				"-e",
				`import {checkDebtRatchet} from ${JSON.stringify(new URL("debt-ratchet.ts", import.meta.url).href)}; checkDebtRatchet(${JSON.stringify(ids)}, "baseline.json", ${JSON.stringify(root)});`,
			],
			{ cwd: root, encoding: "utf8", env: { ...env, PR_BASE_SHA: base } },
		);
	try {
		git("init", "--quiet");
		git("config", "user.email", "fixture@example.test");
		git("config", "user.name", "Fixture");
		git("commit", "--allow-empty", "--quiet", "-m", "initial");
		git("branch", "base");
		baseline(["a"]);
		assert.equal(run(["a"]).status, 0, "initial baseline can be established");
		git("add", "baseline.json");
		git("commit", "--quiet", "-m", "baseline");
		git("branch", "--force", "base");
		baseline(["a", "b"]);
		assert.match(run(["a", "b"]).stderr, /baseline growth is forbidden/u);
		baseline([]);
		assert.equal(run([]).status, 0, "baseline may shrink");
		assert.match(run([], "missing-base").stderr, /git fetch origin main/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
