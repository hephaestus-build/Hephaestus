import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { isPracticeModule } from "../../../../../../docker/agents/precompute/lib/practice-contract.ts";
import type { DiffFile } from "../../../../../../docker/agents/precompute/lib/types.ts";

const repositoryRoot = fileURLToPath(new URL("../../../../../../", import.meta.url));

/** Stages the script beside a `lib/` link, as the runner does, with a context root to read. */
async function stage(context: Record<string, unknown>) {
	const root = mkdtempSync(path.join(tmpdir(), "verify-precompute-"));
	mkdirSync(path.join(root, "practices"));
	mkdirSync(path.join(root, "context"), { recursive: true });
	writeFileSync(path.join(root, "package.json"), '{"type":"module"}\n');
	symlinkSync(path.join(repositoryRoot, "docker/agents/precompute/lib"), path.join(root, "lib"));
	for (const [name, value] of Object.entries(context)) {
		writeFileSync(path.join(root, "context", name), JSON.stringify(value));
	}
	const staged = path.join(root, "practices/states-how-to-verify-the-change.ts");
	cpSync(
		path.join(
			repositoryRoot,
			"server/application/src/main/resources/practices/precompute/states-how-to-verify-the-change.ts",
		),
		staged,
	);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) {
		throw new Error("script does not export a default function");
	}
	return { root, script: mod.default, contextDir: path.join(root, "context") };
}

function changed(file: string, added: string[]): [string, DiffFile] {
	return [
		file,
		{
			path: file,
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
			path.join(root, "repo"),
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
			/one of the kinds the criteria's Occasion section names/u,
		);
		assert.match(result.directions.join("\n"), /testing heading .* has no content/u);
		assert.match(
			result.directions.join("\n"),
			/Issue\(s\) the author names: #4 \(closing keyword in the description, branch\)/u,
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a code change with a preview and a filled testing section is reported as material with its sources", async () => {
	const { root, script, contextDir } = await stage({});
	try {
		const result = await script(
			path.join(root, "repo"),
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
		assert.equal(
			result.directions[0],
			"Files with line changes: 1 code, 1 test, 0 project-configuration, 0 prose, 0 image.",
		);
		assert.match(result.directions.join("\n"), /holds 4 line\(s\) of author text/u);
		assert.match(
			result.directions.join("\n"),
			/preview or story in: App\/Views\/QuizView\.swift\./u,
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("an empty range is named as such", async () => {
	const { root, script, contextDir } = await stage({});
	try {
		const result = await script(path.join(root, "repo"), new Map(), metadata(""), contextDir);
		assert.match(result.directions[0] ?? "", /empty diff is one of the kinds/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

/** The course template's Definition of Done, which shows a closing keyword as an example. */
const TEMPLATE = "## Definition of Done\n\n- [ ] Related issue is linked (e.g., `Closes #12`)\n";

void test("a closing keyword a template shows as an example adopts no issue, and the captured issue is named as not adopted", async () => {
	const { root, script, contextDir } = await stage({
		"linked_work_items.json": {
			workItems: [{ number: 12, title: "Accumulate points based on answer", body: "" }],
		},
	});
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([changed("Trivio/QuizView.swift", ["struct QuizView: View {"])]),
			{ ...metadata(`Closes #<!-- issue number -->\n\n${TEMPLATE}`), source_branch: "branch1" },
			contextDir,
		);
		const directions = result.directions.join("\n");
		assert.doesNotMatch(directions, /adopts|author names/u);
		assert.match(
			directions,
			/Captured as linked_work_items\/<n>\.md but not named by a closing keyword, the title, the branch or a provider closing link: #12\./u,
		);
		assert.equal(result.metrics.namedIssues, 0);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("the author's own closing keyword is named beside the template's example", async () => {
	const { root, script, contextDir } = await stage({
		"linked_work_items.json": {
			workItems: [
				{ number: 12, title: "Save tiles permanently", body: "" },
				{ number: 25, title: "Show tiles on the route", body: "" },
			],
		},
	});
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([changed("App/TileView.swift", ["struct TileView: View {"])]),
			{ ...metadata(`Closes #25\n\n${TEMPLATE}`), source_branch: "tiles" },
			contextDir,
		);
		const directions = result.directions.join("\n");
		assert.match(
			directions,
			/Issue\(s\) the author names: #25 \(closing keyword in the description\)\./u,
		);
		assert.match(directions, /not named by .*: #12\./u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a testing section of only N/A is stated as such, not as a section to judge, and an indented heading is found", async () => {
	const { root, script, contextDir } = await stage({});
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([changed("App/HomeView.swift", ['AlertToast(type: .regular, title: "Hi")'])]),
			metadata(
				"  ## Description\n\nadded AlertToast package to home screen showing a pop up message on the top of the screen\n\n  ## Testing Instructions\n\nN/A\n",
			),
			contextDir,
		);
		const directions = result.directions.join("\n");
		assert.match(directions, /testing section \("## Testing Instructions"\) holds only "N\/A"\./u);
		assert.doesNotMatch(directions, /judge whether/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

/** A file the diff moves or renames without changing a line. */
function moved(file: string): [string, DiffFile] {
	return [file, { path: file, addedLines: new Map(), removedLines: new Map(), hunks: [] }];
}

void test("moved files are counted apart from the files whose lines change, and no direction decides the gate", async () => {
	const { root, script, contextDir } = await stage({});
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				...["Trivio/ContentView.swift", "Trivio/TrivioApp.swift", "Trivio/Info.plist"].map(moved),
				changed("Trivio/Tut1.swift", ["struct Tut1: View {", "#Preview { Tut1() }"]),
				changed("project.yml", ["name: Trivio"]),
				changed("diagrams/class.png", []),
			]),
			metadata(""),
			contextDir,
		);
		assert.equal(
			result.directions[0],
			"Files with line changes: 1 code, 0 test, 1 project-configuration, 0 prose, 1 image.",
		);
		assert.match(
			result.directions[1] ?? "",
			/^3 file\(s\) change no line — moves, renames or binary files: Trivio\/ContentView\.swift, /u,
		);
		assert.doesNotMatch(result.directions.join("\n"), /applies|ENTRY/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a key the code reads at runtime and an ignored secrets file are located, and a literal client id is not", async () => {
	const { root, script, contextDir } = await stage({});
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				changed("Intrinsic/FMPClient.swift", [
					'let clientID = "b3f1c0ffee"',
					'if let secretsURL = Bundle.main.url(forResource: "secrets", withExtension: "plist"),',
				]),
				changed(".gitignore", ["secrets.plist"]),
				changed("App/ContentView.swift", [
					'Button(/*@START_MENU_TOKEN@*/"Button"/*@END_MENU_TOKEN@*/) {',
				]),
			]),
			metadata(""),
			contextDir,
		);
		const directions = result.directions.join("\n");
		assert.match(
			directions,
			/Added lines read a key or secret at runtime: Intrinsic\/FMPClient\.swift:2\. The ignore file gains: secrets\.plist\./u,
		);
		assert.match(
			directions,
			/Added lines keep Xcode placeholder tokens: App\/ContentView\.swift:1\./u,
		);
		assert.equal(result.metrics.secretReadLines, 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
