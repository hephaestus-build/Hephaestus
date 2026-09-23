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
	return { output, summary: await readFile(path.join(output, "summary.md"), "utf8") };
}

/** A script whose result is the given JSON, spelled inline. */
function script(result: object): string {
	return `export default () => (${JSON.stringify(result)});\n`;
}

void test("runner executes a staged practice and writes its public artifact contract", async () => {
	const { output, summary } = await run({
		sample: script({
			hints: [
				{
					file: "inputs/context/general_comments.json",
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
	assert.match(summary, /## sample/u);
	// The header says how the diff view's lines are prefixed, so the model greps for added lines the
	// way they are written.
	assert.match(summary, /an added line matches `\^\\\[L\[0-9\]\+\\\] \\\+`, never `\^\\\+`/u);
	// A changed-line row is cited the way the diff view prints the line; a false flag is left out.
	assert.match(
		summary,
		/- `App\/View\.swift` \[L42\] — print\( \[kind=debug-output\]: `print\("x"\)`/u,
	);
	// A record hint is a row of facts, shown with every flag — a false one is a fact too.
	assert.match(
		summary,
		/\*\*Record facts:\*\*\n- `inputs\/context\/general_comments\.json` — conversation ask: `Please add the confetti` \[by=jennifer, authorReplied=false, threadResolved=true\]/u,
	);
	assert.ok(await readFile(path.join(output, ".complete"), "utf8"));
});

void test("a practice that scanned the diff and found nothing says what it scanned; one that scanned nothing says only its directions", async () => {
	const { summary } = await run({
		scanned: script({ hints: [], metrics: { linesAdded: 120, filesScanned: 4 }, directions: [] }),
		bare: script({ hints: [], metrics: {}, directions: ["no record captured"] }),
	});
	assert.match(
		summary,
		/## scanned\n\nScanned 120 added lines in 4 files for this practice's line patterns; none matched\. A pattern sees one line/u,
	);
	assert.match(summary, /## bare\n\n- no record captured\n\n(?!Nothing matched)/u);
	assert.doesNotMatch(summary, /Nothing matched/u);
});

/** A long row of the given kind, so a handful of practices overrun the budget. */
function row(i: number, inDiff: boolean) {
	return {
		file: inDiff ? `src/file${i}.ts` : "inputs/context/comments.json",
		line: i,
		pattern: inDiff ? "candidate" : "reviewer comment",
		context: "x".repeat(150),
		inDiff,
		flags: { note: "y".repeat(120), later: false },
	};
}

void test("the summary stays under its budget by trimming changed-line rows first, never the JSON pointer nor the last record rows", async () => {
	const record = Array.from({ length: 60 }, (_, i) => row(i, false));
	const inDiff = Array.from({ length: 30 }, (_, i) => row(i, true));
	const scripts = Object.fromEntries(
		Array.from({ length: 8 }, (_, n) => [
			`p${n}`,
			script({ hints: [...record, ...inDiff], metrics: {}, directions: [] }),
		]),
	);
	const { summary } = await run(scripts);
	assert.ok(summary.length <= 20_000, `summary is ${summary.length} chars`);
	// Every practice keeps the pointer to the file that holds all of its rows, and at least three of
	// its record rows: the facts a record practice decides on are never all trimmed away.
	for (let n = 0; n < 8; n += 1) {
		assert.match(summary, new RegExp(`- \\.\\.\\. and \\d+ more in \`[^\`]*/p${n}\\.json\``, "u"));
		assert.match(
			summary,
			new RegExp(`\\*\\*30 hints on changed lines\\*\\* — see \`[^\`]*/p${n}\\.json\``, "u"),
		);
	}
	assert.ok((summary.match(/reviewer comment:/gu)?.length ?? 0) >= 8 * 3);
	// Under budget, twenty record rows are shown before the pointer.
	const small = await run({ one: script({ hints: record, metrics: {}, directions: [] }) });
	assert.equal(small.summary.match(/reviewer comment:/gu)?.length, 20);
	assert.match(small.summary, /- \.\.\. and 40 more in/u);
});
