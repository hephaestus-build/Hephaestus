import assert from "node:assert/strict";
import test from "node:test";

import { duplicatePairs } from "./duplication.ts";
import type { DiffFile } from "./types.ts";

function file(path: string, start: number, lines: string[]): [string, DiffFile] {
	return [
		path,
		{
			path,
			addedLines: new Map(lines.map((text, i) => [start + i, text])),
			removedLines: new Map(),
			hunks: [],
		},
	];
}

const packing = (sep: string, extra: string) => [
	'var current = ""',
	"for piece in pieces {",
	`    if current.count + ${extra} + piece.count <= maxCharacterCount {`,
	`        current += ${sep} + piece`,
	"    } else {",
	"        chunks.append(current)",
	"        current = piece",
	"    }",
	"}",
	"chunks.append(current)",
];

void test("two runs of the same shape in different files are one pair with the names that differ", () => {
	const pairs = duplicatePairs(
		new Map([
			file("App/Preparer.swift", 10, packing(String.raw`"\n\n"`, "2")),
			file("App/Other.swift", 40, [
				"let unrelated = 1",
				...packing(String.raw`"\n"`, "1"),
				"return chunks",
			]),
		]),
	);
	assert.equal(pairs.length, 1);
	const [pair] = pairs;
	assert.ok(pair);
	assert.equal(pair.lines, 10);
	assert.deepEqual(pair.a, { path: "App/Preparer.swift", startLine: 10, endLine: 19 });
	assert.deepEqual(pair.b, { path: "App/Other.swift", startLine: 41, endLine: 50 });
	assert.ok(pair.identicalLines >= 7);
});

void test("a copy inside one file is found once, and short or test-file runs are not pairs", () => {
	const block = [
		"HStack {",
		'    Text("Front").font(.default).bold()',
		'    TextField("Front text", text: $front)',
		"    Spacer()",
		'    Image(systemName: "pencil")',
		"}",
	];
	const inOne = duplicatePairs(
		new Map([
			file("App/View.swift", 1, [
				...block,
				"Divider()",
				...block.map((l) => l.replace("front", "back").replace('Front"', 'Back"')),
			]),
		]),
	);
	assert.equal(inOne.length, 1);
	const [copy] = inOne;
	assert.ok(copy);
	assert.equal(copy.a.startLine, 1);
	assert.equal(copy.b.startLine, 8);
	assert.deepEqual(copy.differing, ["front→back"]);
	const short = duplicatePairs(
		new Map([
			file("App/A.swift", 1, ["guard let x = y else { return }", "let z = x", "z.run()", "}"]),
			file("App/B.swift", 1, ["guard let q = y else { return }", "let w = q", "w.run()", "}"]),
		]),
	);
	assert.equal(short.length, 0);
	const tests = duplicatePairs(
		new Map([file("Tests/ATests.swift", 1, block), file("Tests/BTests.swift", 1, block)]),
	);
	assert.equal(tests.length, 0);
});

void test("parallel code whose names differ everywhere is not a copy", () => {
	const a = [
		"let a = fetch(x)",
		"let b = parse(a)",
		"let c = map(b)",
		"let d = store(c)",
		"return render(d)",
	];
	const b = [
		"let p = load(q)",
		"let r = decode(p)",
		"let s = fold(r)",
		"let t = save(s)",
		"return show(t)",
	];
	assert.equal(
		duplicatePairs(new Map([file("App/A.swift", 1, a), file("App/B.swift", 1, b)])).length,
		0,
	);
});
