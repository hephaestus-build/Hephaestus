import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { glob } from "node:fs/promises";
import path from "node:path";
import { test } from "node:test";

import { isSeq, parseAllDocuments, parseDocument } from "yaml";

const repoRoot = new URL("../", import.meta.url);
const source = (file: string) => readFileSync(new URL(file, repoRoot), "utf8");

// Binary/generated content a `ls1intum/Hephaestus` scan gains nothing from reading.
const SKIPPED_EXTENSIONS = new Set([
	".png",
	".jpg",
	".jpeg",
	".gif",
	".ico",
	".svg",
	".woff",
	".woff2",
	".ttf",
	".eot",
	".pdf",
	".zip",
]);
const GENERATED_DIRS = ["node_modules", "build", ".docusaurus", "storybook-static", "coverage"];

// Sample repository names in stories and test fixtures are not the shipped rename (#1599).
const SAMPLE_DATA = /(\.stories\.tsx|\.test\.tsx?|story-mock-data\.ts)$/;

// Historical records of where a past release's images actually live, or a sample-format doc
// comment — #1599 explicitly excludes both from the rename.
const HISTORICAL_ALLOWLIST = new Set([
	"webapp/src/components/admin/practice-reviews/ReviewArtifact.tsx",
	"docs/admin/compatibility-policy.mdx",
	"docs/admin/release-image-lock.md",
	"docs/decisions/0008-webhook-runtime-role.md",
	"docs/decisions/0018-pg-partman-for-auth-event-partitioning.md",
	"docs/decisions/0031-agent-image-follows-the-deployments-own-tag.md",
]);

await test("default GitHub workspace follows Hephaestus without moving Artemis", () => {
	const documents = parseAllDocuments(
		source("server/application/src/main/resources/application.yml"),
	);
	for (const document of documents) assert.deepEqual(document.errors, []);
	const config = documents[0];
	assert.ok(config);
	const workspacePath = ["hephaestus", "workspace", "default"];
	assert.equal(config.getIn([...workspacePath, "login"]), `\${GITHUB_PAT_LOGIN:hephaestus-build}`);
	const repositories = config.getIn([...workspacePath, "repositories-to-monitor"], true);
	assert.ok(isSeq(repositories));
	assert.deepEqual(repositories.toJSON(), ["hephaestus-build/Hephaestus", "ls1intum/Artemis"]);
});

await test("preview publishing and teardown target the same hostname", () => {
	const teardown = parseDocument(source(".github/workflows/cd-docs-teardown.yml"));
	assert.deepEqual(teardown.errors, []);
	for (const [kind, file, job, teardownVariable] of [
		["docs", "cd-docs.yml", "preview", "PREVIEW_URL"],
		["storybook", "ci-quality-gates.yml", "webapp-stories", "STORYBOOK_PREVIEW_URL"],
	] as const) {
		const publish = parseDocument(source(`.github/workflows/${file}`));
		assert.deepEqual(publish.errors, []);
		const hostname = `hephaestus-build-${kind}-pr-\${{ github.event.number }}.surge.sh`;
		assert.equal(publish.getIn(["jobs", job, "env", "PREVIEW_URL"]), hostname);
		assert.equal(teardown.getIn(["jobs", "teardown", "env", teardownVariable]), hostname);
	}
});

await test("no shipped surface drifts back to ls1intum/Hephaestus", async () => {
	const offenders: string[] = [];
	for (const dir of ["webapp/src", "docs", "docker", ".github"]) {
		const files = await Array.fromAsync(
			glob(`${dir}/**/*`, {
				cwd: repoRoot,
				exclude: (entry) =>
					GENERATED_DIRS.some((generated) => entry.split("/").includes(generated)),
			}),
		);
		for (const file of files.toSorted()) {
			const relative = file.split(path.sep).join("/");
			if (SAMPLE_DATA.test(relative) || HISTORICAL_ALLOWLIST.has(relative)) continue;
			if (SKIPPED_EXTENSIONS.has(path.extname(relative))) continue;
			let content: string;
			try {
				content = source(relative);
			} catch {
				continue; // a directory entry, not a file
			}
			if (/ls1intum\/[Hh]ephaestus/.test(content)) offenders.push(relative);
		}
	}
	assert.deepEqual(
		offenders,
		[],
		"references ls1intum/Hephaestus outside the sample-data and historical-record allowlist",
	);
});
