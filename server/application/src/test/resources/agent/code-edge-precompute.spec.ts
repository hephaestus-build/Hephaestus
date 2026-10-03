import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { isPracticeModule } from "../../../../../../docker/agents/precompute/lib/practice-contract.ts";
import type { DiffFile } from "../../../../../../docker/agents/precompute/lib/types.ts";

const repositoryRoot = fileURLToPath(new URL("../../../../../../", import.meta.url));

async function stage(slug: string) {
	const root = mkdtempSync(path.join(tmpdir(), "code-edge-precompute-"));
	mkdirSync(path.join(root, "practices"));
	mkdirSync(path.join(root, "repo"));
	writeFileSync(path.join(root, "package.json"), '{"type":"module"}\n');
	symlinkSync(path.join(repositoryRoot, "docker/agents/precompute/lib"), path.join(root, "lib"));
	const staged = path.join(root, `practices/${slug}.ts`);
	cpSync(
		path.join(
			repositoryRoot,
			`server/application/src/main/resources/practices/precompute/${slug}.ts`,
		),
		staged,
	);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) {
		throw new Error("script does not export a default function");
	}
	return { root, script: mod.default };
}

const metadata = {
	pr_number: 1,
	pr_url: "https://example.org/team/project/pull/1",
	repository_full_name: "team/project",
	source_branch: "f",
	target_branch: "main",
	commit_sha: "abc123",
};

/** A file whose added lines are numbered from 1, with no hunk context. */
function added(file: string, lines: string[]): [string, DiffFile] {
	return [
		file,
		{
			path: file,
			addedLines: new Map(lines.map((line, index) => [index + 1, line])),
			removedLines: new Map(),
			hunks: [],
		},
	];
}

/** One hunk from new-side line `start`: `+` lines are added, the rest is context. */
function hunk(file: string, start: number, lines: string[]): [string, DiffFile] {
	const addedLines = new Map<number, string>();
	for (const [index, line] of lines.entries()) {
		if (line.startsWith("+")) {
			addedLines.set(start + index, line.slice(1));
		}
	}
	return [
		file,
		{
			path: file,
			addedLines,
			removedLines: new Map(),
			hunks: [
				{
					oldStart: start,
					oldCount: lines.length - addedLines.size,
					newStart: start,
					newCount: lines.length,
					lines,
				},
			],
		},
	];
}

const movies = [
	"return Array(response.cast[0..<2])",
	"let temp = Int(response.current.temp_c)",
	"let ratio = Double(done) / Double(total)",
	"let movie = try! decoder.decode(Movie.self, from: data)",
	'Text("Device shaken! Try again at https://example.com/help")',
	'let url = URL(string: "https://api.example.com")!',
	"let year = Int(yearText) ?? 0",
	"counts[key, default: 0] += 1",
	"let average = sum / items.count",
	"var oldest = Int.max",
	"let score = raw / 20",
	"if let first = items.first {",
];

void test("the crash practice lists the operators the author wrote, with where each runs, and no implicit trap", async () => {
	const { root, script } = await stage("avoids-unsafe-panics-and-chosen-crashes");
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				hunk("App/Views/PersonCardView.swift", 40, [
					" #Preview {",
					"+    let container = try! ModelContainer(for: Person.self, configurations: config)",
					"     return PersonCardView().modelContainer(container)",
					" }",
				]),
				added("App/Services/Movies.swift", movies),
				added("AppTests/ParserTests.swift", ["let item = try! parser.parse(fixture)"]),
			]),
			metadata,
		);
		assert.deepEqual(
			result.hints.map((h) => [h.file, h.line, h.pattern, h.flags]),
			[
				["App/Services/Movies.swift", 4, "swift:try!", { inPreview: false, testFile: false }],
				[
					"App/Services/Movies.swift",
					6,
					"swift:force unwrap",
					{ inPreview: false, testFile: false },
				],
				["App/Views/PersonCardView.swift", 41, "swift:try!", { inPreview: true, testFile: false }],
				["AppTests/ParserTests.swift", 1, "swift:try!", { inPreview: false, testFile: true }],
			],
		);
		const directions = result.directions.join(" ");
		assert.match(directions, /1 sit inside a preview block and 1 in test files/u);
		assert.doesNotMatch(directions, /deliberate-crash|investigate whether|should handle/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a preview opener outside the hunk is read from the pinned file", async () => {
	const { root, script } = await stage("avoids-unsafe-panics-and-chosen-crashes");
	try {
		mkdirSync(path.join(root, "repo/App"));
		writeFileSync(
			path.join(root, "repo/App/GameView.swift"),
			[
				"#Preview {",
				"    let container: ModelContainer",
				"    do {",
				"        container = try ModelContainer(for: GameSession.self)",
				"    } catch {",
				String.raw`        fatalError("Could not create ModelContainer: \(error)")`,
				"    }",
				"    return GameView().modelContainer(container)",
				"}",
			].join("\n"),
		);
		const result = await script(
			path.join(root, "repo"),
			new Map([
				hunk("App/GameView.swift", 5, [
					"     } catch {",
					String.raw`+        fatalError("Could not create ModelContainer: \(error)")`,
					"     }",
				]),
			]),
			metadata,
		);
		assert.deepEqual(result.hints[0]?.flags, { inPreview: true, testFile: false });
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("the input practice lists edge constructs in code only, and none the language already makes optional", async () => {
	const { root, script } = await stage("validates-inputs-and-edge-cases-at-the-boundary");
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				added("App/Services/Movies.swift", [
					...movies,
					"override open func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {",
					"title = try container.decode(String.self, forKey: .title)",
				]),
				added("AppTests/ParserTests.swift", ['XCTAssertEqual(items[0].name, "A")']),
				added("server/routes.ts", ["const id = req.params.id;"]),
			]),
			metadata,
		);
		assert.deepEqual(
			result.hints.map((h) => [h.file, h.line, h.pattern]),
			[
				["App/Services/Movies.swift", 1, "swift:subscript or slice"],
				["App/Services/Movies.swift", 3, "swift:division or remainder by a name"],
				["App/Services/Movies.swift", 4, "swift:payload decode"],
				["App/Services/Movies.swift", 9, "swift:division or remainder by a name"],
				["App/Services/Movies.swift", 10, "swift:extreme sentinel"],
				["server/routes.ts", 1, "typescript:request input"],
			],
		);
		const directions = result.directions.join(" ");
		assert.match(directions, /not a finding/u);
		assert.doesNotMatch(directions, /investigate whether|is absent|exported-function/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("the untrusted-input practice pairs a source with a rendering call, never with a native image template mode", async () => {
	const { root, script } = await stage("validates-and-escapes-untrusted-input");
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				added("App/Settings.swift", [
					'let dark = UserDefaults.standard.bool(forKey: "dark")',
					'Image(systemName: "checkmark").renderingMode(.template)',
				]),
				added("App/Greeting.swift", [
					'let name = UserDefaults.standard.string(forKey: "name") ?? ""',
					String.raw`Text("Hello \(name)")`,
				]),
				added("App/Page.swift", [
					'let html = UserDefaults.standard.string(forKey: "page") ?? ""',
					"webView.loadHTMLString(html, baseURL: nil)",
				]),
				added("server/views.py", [
					'name = request.args.get("name")',
					'return render_template_string("Hello " + name)',
				]),
			]),
			metadata,
		);
		assert.deepEqual(
			result.hints.map((h) => [h.file, h.line, h.pattern]),
			[
				[
					"App/Page.swift",
					2,
					"source→sink: UserDefaults / FileManager / env → WKWebView loadHTMLString / evaluateJavaScript",
				],
				["server/views.py", 2, "source→sink: request/req param → template render"],
			],
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("the error practice lists the fallbacks and bare returns its criteria ask to classify", async () => {
	const { root, script } = await stage("handles-errors-instead-of-swallowing-them");
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				added("App/Parsing.swift", ["return Double(normalizedValue) ?? 0"]),
				added("App/Loader.swift", ["guard let data = cache[key] else { return }"]),
				added("App/Plain.swift", ["let total = values.reduce(0, +)"]),
			]),
			metadata,
		);
		assert.deepEqual(
			result.hints.map((h) => [h.file, h.line, h.pattern]),
			[
				["App/Parsing.swift", 1, "swift:?? fallback on a failable conversion"],
				["App/Loader.swift", 1, "swift:guard … else { return }"],
			],
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
