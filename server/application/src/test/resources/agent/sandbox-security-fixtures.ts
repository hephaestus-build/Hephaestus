import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { cpSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import path from "node:path";

import { environmentForGitFixture } from "../../../../../../scripts/lib/git-environment.ts";
import { GOLDEN_INPUTS, GOLDEN_RECORD, stageGoldenWorkspace } from "./golden-review-workspace.ts";
import { INJECTION, type InjectionSurface } from "./prompt-injection-corpus.ts";

const AGENT = new URL("../../../main/resources/agent/", import.meta.url);

/** A native review workspace. The image supplies the SDK and the practice script toolkit. */
export function stageSecurityReview(
	cwd: string,
	workspaceId: string,
	surface?: InjectionSurface,
	marker = "",
) {
	// The release job installs no packages. Read this one staged contract value without loading zod.
	const schemaVersion = Number(
		/^export const SUPPORTED_SCHEMA_VERSION = (?<version>\d+);$/mu.exec(
			readFileSync(new URL("pi-task-paths.ts", AGENT), "utf8"),
		)?.groups?.version,
	);
	assert.ok(
		Number.isSafeInteger(schemaVersion) && schemaVersion > 0,
		"Cannot read the review task schema version",
	);
	stageGoldenWorkspace(
		cwd,
		schemaVersion,
		readFileSync(new URL("pi-orchestrator.md", AGENT), "utf8"),
	);
	cpSync(AGENT, cwd, {
		recursive: true,
		filter: (source) => !source.includes(`${path.sep}agent${path.sep}work`),
	});
	const write = (file: string, value: string) => {
		mkdirSync(path.dirname(path.join(cwd, file)), { recursive: true });
		writeFileSync(path.join(cwd, file), value);
	};
	write("package.json", '{"type":"module"}\n');
	symlinkSync("/opt/pi-sdk/node_modules", path.join(cwd, "node_modules"));
	for (const directory of ["lib", "kits"]) {
		symlinkSync(
			`/opt/practice-scripts/${directory}`,
			path.join(cwd, "work/practice-scripts", directory),
		);
	}
	const repository = path.join(cwd, "repos/primary");
	rmSync(path.join(repository, ".git"), { recursive: true });
	const git = (...args: string[]) =>
		execFileSync("git", ["-C", repository, ...args], {
			encoding: "utf8",
			env: environmentForGitFixture({
				GIT_AUTHOR_NAME: "Fixture",
				GIT_AUTHOR_EMAIL: "fixture@example.invalid",
				GIT_COMMITTER_NAME: "Fixture",
				GIT_COMMITTER_EMAIL: "fixture@example.invalid",
			}),
		}).trim();
	git("init", "-q", "-b", "main");
	write("repos/primary/src/Auth.java", "checked();\n");
	git("add", ".");
	git("commit", "-qm", "Initial fixture");
	const base = git("rev-parse", "HEAD");
	write("repos/primary/src/Auth.java", "insecure();\n");
	write("repos/primary/INSTRUCTIONS.md", surface === "file" ? INJECTION : "Review this work.\n");
	git("add", ".");
	git("commit", "-qm", "Add authentication call");
	const head = git("rev-parse", "HEAD");
	write("evidence/change.json", JSON.stringify({ base_sha: base, head_sha: head }));
	write("evidence/description.md", surface === "body" ? INJECTION : "Review this work.\n");
	write(
		"evidence/comments.json",
		JSON.stringify([{ body: surface === "comment" ? INJECTION : "Please review." }]),
	);
	write(
		"evidence/metadata.json",
		JSON.stringify({ ...GOLDEN_RECORD, commit_sha: head, body: marker }),
	);
	write(
		"work/practice-scripts/practices/test-practice.inputs.json",
		JSON.stringify({
			...GOLDEN_INPUTS,
			item: { ...GOLDEN_RECORD, commit_sha: head, body: marker },
			scope: { ...GOLDEN_INPUTS.scope, version: head, baseVersion: base },
		}),
	);
	write(
		"catalog/practices/index.json",
		JSON.stringify([
			{
				slug: "test-practice",
				name: "Authentication review",
				versionId: "7",
				readsSources: [
					"scm.pull-request.core",
					"scm.pull-request.diff",
					"scm.pull-request.discussion",
					"scm.repository.tree",
				],
				exhaustiveSources: ["scm.pull-request.diff"],
			},
		]),
	);
	const task: unknown = JSON.parse(readFileSync(path.join(cwd, "task.json"), "utf8"));
	if (typeof task !== "object" || task === null || Array.isArray(task)) {
		throw new Error("Invalid fixture task");
	}
	write("task.json", JSON.stringify({ ...task, workspaceId }));
	for (const directory of ["out", ".pi", ".sessions", "work/notes", "work/review-input"]) {
		mkdirSync(path.join(cwd, directory), { recursive: true });
	}
}
