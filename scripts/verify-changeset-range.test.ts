import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import { environmentForGitFixture } from "./lib/git-environment.ts";

const bash = spawnSync("bash", ["--noprofile", "--norc", "-c", "mapfile -t _ < /dev/null"], {
	encoding: "utf8",
	env: environmentForGitFixture(),
});

void test(
	"checks release changes against the tested merge base",
	{ skip: bash.error !== undefined || bash.status !== 0 },
	async (t) => {
		const workflow = parseDocument(
			await readFile(".github/workflows/verify-changesets.yml", "utf8"),
		);
		const caller = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
		assert.equal(caller.getIn(["jobs", "Changesets", "if"]), "github.event_name == 'pull_request'");
		const jobPath = ["jobs", "verify-changesets"];
		assert.match(String(workflow.getIn([...jobPath, "steps", 0, "uses"])), /^actions\/checkout@/);
		assert.equal(workflow.getIn([...jobPath, "steps", 0, "with", "fetch-depth"]), 0);
		assert.equal(workflow.getIn([...jobPath, "steps", 0, "with", "ref"]), undefined);
		const base = workflow.getIn([...jobPath, "env", "BASE_SHA"]);
		assert.ok(typeof base === "string");
		const steps = workflow.getIn([...jobPath, "steps"]);
		assert.ok(isSeq(steps));
		for (const step of steps.items) {
			assert.ok(isMap(step));
			assert.equal(step.getIn(["env", "BASE_SHA"]), undefined);
		}
		const releaseStep = steps.items.find(
			(step) => isMap(step) && step.get("name") === "Check release-note presence",
		);
		assert.ok(isMap(releaseStep));
		const shell = releaseStep.get("run");
		assert.ok(typeof shell === "string");
		const directory = await mkdtemp(path.join(tmpdir(), "changeset-merge-"));
		t.after(() => rm(directory, { recursive: true, force: true }));
		const env = environmentForGitFixture();
		const git = (...args: string[]): string => {
			const result = spawnSync("git", args, { cwd: directory, encoding: "utf8", env });
			assert.equal(result.status, 0, result.stderr);
			return result.stdout.trim();
		};
		const write = async (file: string, content: string): Promise<void> => {
			await mkdir(path.dirname(path.join(directory, file)), { recursive: true });
			await writeFile(path.join(directory, file), content);
		};
		const check = (comparison = base) =>
			spawnSync("bash", ["--noprofile", "--norc", "-e", "-c", shell], {
				cwd: directory,
				encoding: "utf8",
				env: { ...env, BASE_SHA: comparison },
			});

		git("init", "--quiet", "--initial-branch=main");
		git("config", "user.name", "Test");
		git("config", "user.email", "test@example.invalid");
		await write("MIGRATION.md", "# Migration\n");
		await write(".changeset/previous.md", "Pending release note\n");
		git("add", ".");
		git("commit", "--quiet", "-m", "base");
		const eventBase = git("rev-parse", "HEAD");
		git("checkout", "--quiet", "-b", "feature");
		await write("README.md", "Documentation change\n");
		git("add", ".");
		git("commit", "--quiet", "-m", "feature");
		git("checkout", "--quiet", "main");
		await write("MIGRATION.md", "# Migration\n\nReleased upgrade instructions\n");
		git("rm", "--quiet", ".changeset/previous.md");
		git("add", ".");
		git("commit", "--quiet", "-m", "release");
		git("merge", "--quiet", "--no-ff", "feature", "-m", "PR merge");

		const valid = check();
		assert.equal(valid.status, 0, valid.stdout + valid.stderr);
		assert.notEqual(check(eventBase).status, 0, "the fixture must expose a stale-base comparison");

		git("checkout", "--quiet", "-b", "invalid-migration");
		await write("MIGRATION.md", "Edited by the feature\n");
		git("add", ".");
		git("commit", "--quiet", "-m", "edit migration");
		git("checkout", "--quiet", "main");
		git("merge", "--quiet", "--no-ff", "invalid-migration", "-m", "PR merge");
		const migration = check();
		assert.notEqual(migration.status, 0);
		assert.match(migration.stdout, /Do not edit MIGRATION\.md/);

		git("checkout", "--quiet", "-b", "missing-note");
		await write("server/Service.java", "class Service {}\n");
		git("add", ".");
		git("commit", "--quiet", "-m", "shipped change without a note");
		git("checkout", "--quiet", "main");
		git("merge", "--quiet", "--no-ff", "missing-note", "-m", "PR merge");
		const missing = check();
		assert.notEqual(missing.status, 0);
		assert.match(missing.stdout, /changes shipped code but carries no changeset/);
	},
);
