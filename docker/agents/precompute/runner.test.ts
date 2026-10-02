import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

/** Stages the given scripts under `<root>/out/practices` and runs the runner on them. */
async function run(scripts: Record<string, string>) {
	const root = await mkdtemp(path.join(tmpdir(), "precompute-runner-"));
	const output = path.join(root, "out");
	await mkdir(path.join(output, "practices"), { recursive: true });
	await writeFile(path.join(root, "package.json"), '{"type":"module"}\n');
	for (const [slug, source] of Object.entries(scripts)) {
		await writeFile(path.join(output, "practices", `${slug}.ts`), source);
	}
	const { status, stderr } = spawnSync(
		process.execPath,
		[path.join(import.meta.dirname, "runner.ts"), "--repo", root, "--output", output],
		{ encoding: "utf8", stdio: ["ignore", "ignore", "pipe"] },
	);
	assert.equal(status, 0, stderr);
	return {
		output,
		section: async (slug: string) => readFile(path.join(output, `${slug}.md`), "utf8"),
	};
}

/** A script whose result is the given JSON, spelled inline. */
function script(result: object): string {
	return `export default () => (${JSON.stringify(result)});\n`;
}

void test("runner executes a staged practice and writes its public artifact contract", async () => {
	const { output, section } = await run({
		sample: script({
			hints: [
				{
					file: "context/general_comments.json",
					line: 0,
					pattern: "conversation ask",
					context: "Please add the confetti",
					inDiff: false,
					flags: { by: "jennifer", authorReplied: false, threadResolved: true },
				},
				{
					file: "App/View.swift",
					line: 42,
					pattern: "print(",
					context: 'print("x")',
					inDiff: true,
					flags: { kind: "debug-output", inScope: false },
				},
			],
			metrics: { found: 1 },
			directions: ["inspect sample"],
		}),
	});
	const written: unknown = JSON.parse(await readFile(path.join(output, "sample.json"), "utf8"));
	assert.ok(typeof written === "object" && written !== null);
	assert.equal(Reflect.get(written, "practice"), "sample");
	assert.equal(Reflect.get(written, "status"), "ok");
	assert.deepEqual(Reflect.get(written, "metrics"), { found: 1 });
	const summary = await section("sample");
	assert.match(summary, /^- inspect sample\n/u);
	// A changed-line row is cited the way the diff view prints the line; a false flag is left out.
	assert.match(
		summary,
		/- `App\/View\.swift` \[L42\] — print\( \[kind=debug-output\]: `print\("x"\)`/u,
	);
	// A record hint is a row of facts, shown with every flag — a false one is a fact too.
	assert.match(
		summary,
		/\*\*Record facts:\*\*\n- `context\/general_comments\.json` — conversation ask: `Please add the confetti` \[by=jennifer, authorReplied=false, threadResolved=true\]/u,
	);
	assert.ok(await readFile(path.join(output, ".complete"), "utf8"));
});

void test("a practice that scanned the diff and found nothing says what it scanned; one that scanned nothing says only its directions", async () => {
	const { section } = await run({
		scanned: script({ hints: [], metrics: { linesAdded: 120, filesScanned: 4 }, directions: [] }),
		bare: script({ hints: [], metrics: {}, directions: ["no record captured"] }),
	});
	assert.match(
		await section("scanned"),
		/^Scanned 120 added lines in 4 files for this practice's line patterns; none matched\. A pattern sees one line/u,
	);
	assert.equal(await section("bare"), "- no record captured\n");
});

/** A long row of the given kind, so a handful of practices overrun the budget. */
function row(i: number, inDiff: boolean, width = 150) {
	return {
		file: inDiff ? `src/file${i}.ts` : "context/comments.json",
		line: i,
		pattern: inDiff ? "candidate" : "reviewer comment",
		context: "x".repeat(width),
		inDiff,
		flags: { note: "y".repeat(width - 30), later: false },
	};
}

void test("each practice's section stays under its own budget, keeping a sample of changed-line rows, record rows, and the JSON pointer", async () => {
	const record = Array.from({ length: 60 }, (_, i) => row(i, false));
	const inDiff = Array.from({ length: 30 }, (_, i) => row(i, true));
	const scripts = Object.fromEntries(
		Array.from({ length: 8 }, (_, n) => [
			`p${n}`,
			script({ hints: [...record, ...inDiff], metrics: {}, directions: [] }),
		]),
	);
	const { section } = await run(scripts);
	// One busy practice does not trim another: each is bounded alone and keeps a sample of both kinds.
	for (let n = 0; n < 8; n += 1) {
		const text = await section(`p${n}`);
		assert.ok(text.length <= 3000, `p${n} is ${text.length} chars`);
		assert.match(text, new RegExp(`- \\.\\.\\. and \\d+ more in \`[^\`]*/p${n}\\.json\``, "u"));
		assert.match(
			text,
			new RegExp(`\\*\\*30 hints on changed lines\\*\\* — see \`[^\`]*/p${n}\\.json\``, "u"),
		);
		assert.equal(text.match(/candidate:/gu)?.length, 5);
		assert.ok((text.match(/reviewer comment:/gu)?.length ?? 0) >= 3);
	}
	// Under budget, twenty record rows are shown before the pointer.
	const small = await run({
		one: script({
			hints: Array.from({ length: 22 }, (_, i) => row(i, false, 40)),
			metrics: {},
			directions: [],
		}),
	});
	const one = await small.section("one");
	assert.equal(one.match(/reviewer comment:/gu)?.length, 20);
	assert.match(one, /- \.\.\. and 2 more in/u);
});

void test("a section whose directions alone overrun the budget is cut and points at the full result", async () => {
	const { section } = await run({
		lines: script({
			hints: [],
			metrics: {},
			directions: Array.from({ length: 10 }, (_, i) => `${i} ${"d".repeat(400)}`),
		}),
		one: script({ hints: [], metrics: {}, directions: ["e".repeat(5000)] }),
	});
	for (const slug of ["lines", "one"]) {
		const text = await section(slug);
		assert.ok(text.length <= 3000, `${slug} is ${text.length} chars`);
		assert.match(
			text,
			new RegExp(`\\n- \\.\\.\\. the rest is in \`[^\`]*/${slug}\\.json\`\\n$`, "u"),
		);
	}
	assert.match(await section("lines"), /^- 0 d+\n/u);
	assert.match(await section("one"), /^- e{100}/u);
});
