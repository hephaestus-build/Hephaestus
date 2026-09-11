import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import { environmentForGitFixture } from "./lib/git-environment.ts";

const checker = join(import.meta.dirname, "assert-generated-clean.ts");
const apiPaths = ["server/openapi.yaml", "webapp/src/api"];
const erdPath = "docs/contributor/erd/schema.mmd";

function fixture() {
	const repo = mkdtempSync(join(tmpdir(), "generated-clean-"));
	const env = environmentForGitFixture();
	const git = (...args: string[]) =>
		execFileSync("git", args, {
			cwd: repo,
			env,
			encoding: "utf8",
			stdio: ["ignore", "pipe", "pipe"],
		});
	git("init", "--quiet", "--initial-branch=main");
	git("config", "user.name", "Test");
	git("config", "user.email", "test@example.invalid");
	for (const file of [
		"server/openapi.yaml",
		"webapp/src/api/client.ts",
		erdPath,
		"unrelated.txt",
	]) {
		mkdirSync(dirname(join(repo, file)), { recursive: true });
		writeFileSync(join(repo, file), "original\n");
	}
	git("add", ".");
	git("commit", "--quiet", "-m", "initial");
	return {
		repo,
		git,
		check: (paths: string[], subdirectory = ".") =>
			spawnSync(process.execPath, [checker, ...paths], {
				cwd: join(repo, subdirectory),
				env,
				encoding: "utf8",
			}),
		index: () => readFileSync(join(repo, ".git/index")),
	};
}

for (const file of ["server/openapi.yaml", "webapp/src/api/client.ts", erdPath]) {
	for (const change of ["modified", "deleted", "staged"]) {
		void test(`rejects ${change} ${file} without changing the index`, (t) => {
			const { repo, git, check, index } = fixture();
			t.after(() => rmSync(repo, { recursive: true, force: true }));
			if (change === "deleted") rmSync(join(repo, file));
			else writeFileSync(join(repo, file), "generated change\n");
			if (change === "staged") git("add", file);
			const before = index();
			const result = check(file === erdPath ? [erdPath] : apiPaths, "server");
			assert.equal(result.status, 1, result.stderr);
			assert.ok(result.stderr.includes(file.split("/").at(-1) ?? file));
			assert.deepEqual(index(), before);
		});
	}
}

for (const change of ["added", "renamed"]) {
	void test(`rejects a generated client file that is ${change} without staging it`, (t) => {
		const { repo, check, index } = fixture();
		t.after(() => rmSync(repo, { recursive: true, force: true }));
		const added = join(repo, "webapp/src/api/new.ts");
		if (change === "renamed") renameSync(join(repo, "webapp/src/api/client.ts"), added);
		else writeFileSync(added, "new client\n");
		const before = index();
		assert.equal(check(apiPaths).status, 1);
		assert.deepEqual(index(), before);
	});
}

void test("API and ERD verdicts ignore unrelated drift and remain independent", (t) => {
	const { repo, git, check, index } = fixture();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	writeFileSync(join(repo, "unrelated.txt"), "unrelated staged change\n");
	git("add", "unrelated.txt");
	writeFileSync(join(repo, "untracked.txt"), "unrelated new file\n");
	const before = index();
	assert.equal(check(apiPaths).status, 0);
	assert.equal(check([erdPath]).status, 0);
	writeFileSync(join(repo, "server/openapi.yaml"), "API drift\n");
	assert.equal(check(apiPaths).status, 1);
	assert.equal(check([erdPath]).status, 0);
	writeFileSync(join(repo, erdPath), "ERD drift\n");
	assert.equal(check(apiPaths).status, 1);
	assert.equal(check([erdPath]).status, 1);
	assert.deepEqual(index(), before);
});

void test("a missing path argument is an error rather than a whole-tree check", (t) => {
	const { repo, check } = fixture();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	const result = check([]);
	assert.notEqual(result.status, 0);
	assert.match(result.stderr, /Name the generated paths/);
});

void test("both workflow checks are path-scoped and keep independent outcomes and the packaged JAR", () => {
	const workflow = parseDocument(readFileSync(".github/workflows/ci-build.yml", "utf8"));
	const steps = workflow.getIn(["jobs", "server-api", "steps"]);
	assert.ok(isSeq(steps));
	for (const [id, paths] of [
		["openapi", apiPaths],
		["schema", [erdPath]],
	] as const) {
		const step: unknown = steps.items.find((item) => isMap(item) && item.get("id") === id);
		assert.ok(isMap(step));
		assert.equal(step.get("continue-on-error"), true);
		const run = String(step.get("run"));
		assert.ok(run.includes(`node scripts/assert-generated-clean.ts ${paths.join(" ")}`));
		assert.doesNotMatch(run, /git add|git diff --cached/);
		if (id === "openapi") {
			assert.equal(
				step.getIn(["env", "HEPHAESTUS_APPLICATION_JAR"]),
				`\${{ steps.server.outputs.executable-jar }}`,
			);
			assert.match(run, /vp run generate:api/);
		} else {
			assert.equal(step.get("if"), "always()");
			assert.match(run, /vp run db:check-drift/);
			assert.match(run, /vp run db:generate-erd-docs/);
			assert.match(run, /trap 'docker stop postgres-db.*docker rm postgres-db/);
		}
	}
	const verdict = steps.items.find(
		(item) => isMap(item) && item.get("name") === "Evaluate generated artifacts",
	);
	assert.ok(isMap(verdict));
	assert.equal(verdict.get("if"), "always()");
	assert.equal(verdict.getIn(["env", "OPENAPI"]), `\${{ steps.openapi.outcome }}`);
	assert.equal(verdict.getIn(["env", "SCHEMA"]), `\${{ steps.schema.outcome }}`);
});

void test("an unmatched generated path fails even when another path matches", (t) => {
	const { repo, check, index } = fixture();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	const before = index();
	assert.notEqual(check(["server/openapi.yaml", "docs/contributor/erd/missing.mmd"]).status, 0);
	assert.deepEqual(index(), before);
});

void test("no-op regeneration passes without refreshing the index", (t) => {
	const { repo, check, index } = fixture();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	const before = index();
	writeFileSync(join(repo, "server/openapi.yaml"), "original\n");
	assert.equal(check(apiPaths).status, 0);
	assert.deepEqual(index(), before);
});
