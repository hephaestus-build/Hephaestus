import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { isPracticeModule } from "../../../../../../docker/agents/precompute/lib/practice-contract.ts";
import type { DiffFile } from "../../../../../../docker/agents/precompute/lib/types.ts";

const repositoryRoot = fileURLToPath(new URL("../../../../../../", import.meta.url));

/** Stages the script beside a `lib/` link, as the runner does, with a context root to read. */
async function stage(context: Record<string, unknown>) {
	const root = mkdtempSync(join(tmpdir(), "verify-precompute-"));
	mkdirSync(join(root, "practices"));
	mkdirSync(join(root, "context"), { recursive: true });
	writeFileSync(join(root, "package.json"), '{"type":"module"}\n');
	symlinkSync(join(repositoryRoot, "docker/agents/precompute/lib"), join(root, "lib"));
	for (const [name, value] of Object.entries(context)) {
		writeFileSync(join(root, "context", name), JSON.stringify(value));
	}
	const staged = join(root, "practices/states-how-to-verify-the-change.ts");
	cpSync(
		join(
			repositoryRoot,
			"server/application/src/main/resources/practices/precompute/states-how-to-verify-the-change.ts",
		),
		staged,
	);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) throw new Error("script does not export a default function");
	return { root, script: mod.default, contextDir: join(root, "context") };
}

function changed(path: string, added: string[]): [string, DiffFile] {
	return [
		path,
		{
			path,
			addedLines: new Map(added.map((line, index) => [index + 1, line])),
			removedLines: new Map(),
			hunks: [],
		},
	];
}

const metadata = (body: string) => ({
	pr_number: 4,
	pr_url: "https://example.org/team/project/-/merge_requests/4",
	repository_full_name: "team/project",
	source_branch: "4-day-2-aom",
	target_branch: "main",
	commit_sha: "abc123",
	body,
});

void test("a diagram-and-README change is named as an occasion-gate kind, with the empty testing heading called out", async () => {
	const { root, script, contextDir } = await stage({
		"linked_work_items.json": { workItems: [{}] },
	});
	try {
		const result = await script(
			join(root, "repo"),
			new Map([
				changed("diagrams/aom.png", ["PNG"]),
				changed("README.md", ["![AOM](diagrams/aom.png)"]),
			]),
			metadata("## Description\n\nCloses #4\n\n## Testing Instructions\n\n<!-- steps -->\n"),
			contextDir,
		);
		assert.equal(result.metrics.codeFiles, 0);
		assert.equal(result.metrics.imageFiles, 1);
		assert.match(
			result.directions[0] ?? "",
			/one of the kinds the criteria's Occasion section names/,
		);
		assert.match(result.directions.join("\n"), /testing heading .* with no content/);
		assert.match(
			result.directions.join("\n"),
			/adopts an issue with a closing keyword \("Closes #4"\)/,
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a code change with a preview and a filled testing section is reported as material with its sources", async () => {
	const { root, script, contextDir } = await stage({});
	try {
		const result = await script(
			join(root, "repo"),
			new Map([
				changed("App/Views/QuizView.swift", ["struct QuizView: View {", "#Preview { QuizView() }"]),
				changed("App/Tests/QuizTests.swift", ["func testScore() {}"]),
			]),
			metadata(
				"## Testing Instructions\n\n1. Open the Quiz tab\n2. Answer three questions\n3. The score reads 3/3\n\n![result](/uploads/x.png)\n",
			),
			contextDir,
		);
		assert.equal(result.metrics.codeFiles, 1);
		assert.equal(result.metrics.testFiles, 1);
		assert.equal(result.metrics.previewFiles, 1);
		assert.equal(result.metrics.testingSectionLines, 4);
		assert.equal(result.metrics.screenshotsInDescription, 1);
		assert.match(result.directions[0] ?? "", /The practice applies/);
		assert.match(result.directions.join("\n"), /QuizView\.swift — an ENTRY/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("an empty range is named as such", async () => {
	const { root, script, contextDir } = await stage({});
	try {
		const result = await script(join(root, "repo"), new Map(), metadata(""), contextDir);
		assert.match(result.directions[0] ?? "", /empty diff is one of the kinds/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
