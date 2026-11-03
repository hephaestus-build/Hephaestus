import assert from "node:assert/strict";
import { test } from "node:test";

import { sourceFile, type SiteRule } from "./sites.ts";

const every = () => true;
const rule = (label: string, pattern: RegExp): SiteRule => ({ kind: label, label, test: pattern });
const added =
	(...lines: number[]) =>
	(line: number) =>
		lines.includes(line);

void test("whole-file matching keeps repeated and multi-line sites at their own extent", () => {
	const file = sourceFile(
		"src/a.ts",
		"call(); call();\ntry {\n work();\n} catch {\n}\n// call();\nconst note = 'call();';",
		every,
	);
	assert.deepEqual(
		file
			.sites([rule("call", /call\(\)/u), rule("empty handler", /catch\s*\{\s*\}/u)])
			.map(({ label, line, endLine }) => [label, line, endLine]),
		[
			["call", 1, 1],
			["call", 1, 1],
			["empty handler", 4, 5],
		],
	);
});

void test("regex state is reset for each file and flags are preserved", () => {
	const expression = /CALL/giu;
	expression.lastIndex = 4;
	for (const path of ["a.ts", "b.ts"]) {
		assert.equal(sourceFile(path, "call call", every).sites([rule("call", expression)]).length, 2);
	}
	assert.equal(expression.lastIndex, 4);
});

void test("a change keeps a multi-line site only when its own extent intersects added lines", () => {
	const file = sourceFile(
		"src/a.ts",
		"function work() {\n call();\n another();\n}\ncatch {\n}",
		added(3, 6),
	);
	assert.deepEqual(
		file
			.sites([rule("call", /call\(\)/u), rule("handler", /catch\s*\{\s*\}/u)])
			.map((site) => site.label),
		["handler"],
	);
});

void test("declaration location uses the whole source and does not enlarge a call's extent", () => {
	const file = sourceFile(
		"Screen.swift",
		"struct Screen: View {\n func load() {\n  URLSession.shared.dataTask(with: url)\n }\n}\nclass Store {\n func load() { URLSession.shared.dataTask(with: url) }\n}",
		every,
	);
	const placed = file.sites([rule("request", /URLSession\b/u)], {
		enclosing: { scope: /\bView\b/u, only: true },
	});
	assert.equal(placed.length, 1);
	assert.equal(placed[0]?.line, 3);
	assert.equal(placed[0].endLine, 3);
	assert.equal(placed[0].facts.enclosing, "struct Screen");
});

void test("comment rules opt into comments without joining distant lines", () => {
	const file = sourceFile("a.ts", "// TODO tracked work\ncatch {\n run();\n}\n// catch {}", every);
	assert.equal(file.sites([rule("note", /TODO/u)], { comments: true }).length, 1);
	assert.equal(file.sites([rule("handler", /catch\s*\{\s*\}/u)]).length, 0);
});

void test("Python and Ruby literals and comments are not code", () => {
	for (const path of ["source.py", "source.rb"]) {
		const file = sourceFile(path, "# call()\nvalue = 'call()'\ncall()\ncall() # call()", every);
		assert.deepEqual(
			file.sites([rule("call", /call\(\)/u)]).map((site) => site.line),
			[3, 4],
		);
	}
});

void test("a consuming site's extent includes arguments changed below its callee", () => {
	const text = "load(\n  source,\n  options\n);\nother();";
	const load = [rule("load", /load\s*\(/u)];
	assert.deepEqual(
		sourceFile("a.ts", text, every)
			.sites(load)
			.map((site) => [site.line, site.endLine]),
		[[1, 4]],
	);
	assert.equal(sourceFile("a.ts", text, added(3)).sites(load).length, 1);
	assert.equal(sourceFile("a.ts", text, added(5)).sites(load).length, 0);
});

void test("executed expressions inside Swift and JavaScript interpolation remain code", () => {
	const load = [rule("load", /load\s*\(/u)];
	for (const [path, text] of [
		["source.swift", String.raw`Text("literal load() and \(load("name"))")`],
		["source.ts", `const text = \`literal load() and \${load("name")}\``],
	] as const) {
		assert.equal(sourceFile(path, text, every).sites(load).length, 1);
	}
});

void test("the first rule alone keeps a span that two rules match", () => {
	const file = sourceFile("a.ts", "fetch(url);", every);
	const rules = [rule("first", /fetch\(/u), rule("second", /fetch\(url/u)];
	assert.deepEqual(
		file.sites(rules, { overlap: "first" }).map((site) => site.label),
		["first"],
	);
	assert.equal(file.sites(rules).length, 2);
});

void test("an apostrophe in text does not hide the code on the next lines", () => {
	for (const [path, text] of [
		["page.tsx", "const p = <p>don't</p>;\nload();\n"],
		["quote.js", "x.replace(/['\"]/g, '');\nload();\n"],
		["tool.py", "note = 'it\nload()\n"],
	] as const) {
		const file = sourceFile(path, text, every);
		assert.deepEqual(
			file.sites([rule("load", /\bload\(/u)]).map((site) => site.line),
			[2],
			path,
		);
	}
});

void test("a Rust quote character is not a string, and a lifetime opens nothing", () => {
	const file = sourceFile(
		"lib.rs",
		"fn f<'a>(s: &'a str) {\n let c = '\"';\n s.unwrap();\n}\n",
		every,
	);
	assert.deepEqual(
		file.sites([rule("unwrap", /\.unwrap\(\)/u)]).map((site) => site.line),
		[3],
	);
});

void test("a catch that holds only a comment matches a rule for an empty catch", () => {
	const empty = rule("empty-catch", /\bcatch\s*(?:\([^)]*\))?\s*\{\s*\}/u);
	for (const text of [
		"try { run(); } catch {}\n",
		"try { run(); } catch (e) { /* ignored */ }\n",
		"try {\n run();\n} catch (e) {\n // ignored\n}\n",
	]) {
		assert.equal(sourceFile("a.ts", text, every).sites([empty]).length, 1, text);
	}
	assert.equal(sourceFile("a.ts", "// catch {}\n", every).sites([empty]).length, 0);
	assert.equal(sourceFile("a.ts", 'const s = "catch {}";\n', every).sites([empty]).length, 0);
	assert.equal(
		sourceFile("a.ts", "try { run(); } catch { log(); }\n", every).sites([empty]).length,
		0,
	);
});

void test("a rule can still quote a string", () => {
	const quoted = [rule("label", /\bLabel\s*\(\s*"/u)];
	assert.equal(
		sourceFile("a.swift", 'Label("Pay", systemImage: "pay")\n', every).sites(quoted).length,
		1,
	);
});

for (const path of [
	"a.swift",
	"a.kt",
	"a.java",
	"a.ts",
	"a.js",
	"a.cs",
	"a.go",
	"a.rs",
	"a.py",
	"a.rb",
	"a.php",
	"a.m",
	"a.c",
]) {
	void test(`${path}: a comment and a string are not code`, () => {
		const comment = path.endsWith(".py") || path.endsWith(".rb") ? "#" : "//";
		const file = sourceFile(path, `${comment} load()\nnote = "load()"\nload()\n`, every);
		assert.deepEqual(
			file.sites([rule("load", /\bload\(\)/u)]).map((site) => site.line),
			[3],
		);
	});
}

void test("a regular expression literal is not code", () => {
	const file = sourceFile("a.ts", "const call = /load()/u;\nload();\n", every);
	assert.deepEqual(
		file.sites([rule("load", /\bload\(\)/u)]).map((site) => site.line),
		[2],
	);
});
