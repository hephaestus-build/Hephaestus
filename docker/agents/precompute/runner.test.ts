import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { createInterface } from "node:readline";
import { after, test } from "node:test";

import { isJsonObject } from "./lib/json.ts";
import { MAX_ERROR_CHARS } from "./lib/script-run.ts";
import { stagePrecompute } from "./stage.ts";

const runner = path.join(import.meta.dirname, "runner.ts");

const roots: string[] = [];
after(async () =>
	Promise.all(roots.map(async (root) => rm(root, { recursive: true, force: true }))),
);

/** Stages the scripts, and the change when given, and returns the runner's arguments. */
async function stage(scripts: Record<string, string>, diff?: string) {
	const { root, practices } = await stagePrecompute(scripts);
	roots.push(root);
	const output = path.join(root, "out");
	const args = [runner, "--repo", root, "--practices", practices, "--output", output];
	if (diff !== undefined) {
		await writeFile(path.join(root, "change.diff"), diff);
		args.push("--diff", path.join(root, "change.diff"));
	}
	return {
		root,
		output,
		args,
		section: async (slug: string) => readFile(path.join(output, `${slug}.md`), "utf8"),
		json: async (slug: string) => {
			const parsed: unknown = JSON.parse(await readFile(path.join(output, `${slug}.json`), "utf8"));
			assert.ok(isJsonObject(parsed), `${slug}.json holds an object`);
			return parsed;
		},
	};
}

/** Stages the scripts and runs the runner on them to the end. */
async function run(scripts: Record<string, string>, diff?: string, extra: string[] = []) {
	const staged = await stage(scripts, diff);
	const { status, stderr } = spawnSync(process.execPath, [...staged.args, ...extra], {
		encoding: "utf8",
		stdio: ["ignore", "ignore", "pipe"],
	});
	assert.equal(status, 0, stderr);
	return staged;
}

/** A unified diff that adds `lines` lines to each named file. */
function additions(files: Record<string, number>): string {
	return Object.entries(files)
		.map(([file, lines]) =>
			[
				`diff --git a/${file} b/${file}`,
				"--- /dev/null",
				`+++ b/${file}`,
				`@@ -0,0 +1,${lines} @@`,
				...Array.from({ length: lines }, (_, i) => `+line ${i + 1}`),
			].join("\n"),
		)
		.join("\n");
}

/** A script whose result is the given JSON, spelled inline. */
function script(result: object): string {
	return `export default () => (${JSON.stringify(result)});\n`;
}

void test("runner executes a staged practice and writes its public artifact contract", async () => {
	const { output, section } = await run(
		{
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
		},
		additions({ "App/View.swift": 42 }),
	);
	const written: unknown = JSON.parse(await readFile(path.join(output, "sample.json"), "utf8"));
	assert.ok(typeof written === "object" && written !== null);
	assert.equal(Reflect.get(written, "practice"), "sample");
	assert.equal(Reflect.get(written, "status"), "ok");
	assert.equal(Reflect.get(written, "contract"), "positional");
	assert.equal(Reflect.get(written, "dropped"), 0);
	assert.equal(typeof Reflect.get(written, "durationMs"), "number");
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
		line: inDiff ? i + 1 : i,
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
	const { section } = await run(
		scripts,
		additions(Object.fromEntries(Array.from({ length: 30 }, (_, i) => [`src/file${i}.ts`, i + 1]))),
	);
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

void test("a hint on a changed line that the change does not hold is not shown, and the section says so", async () => {
	const { section } = await run(
		{
			cited: script({
				hints: [
					{ file: "src/a.ts", line: 2, pattern: "kept", context: "x", inDiff: true, flags: {} },
					{ file: "src/a.ts", line: 9, pattern: "invented", context: "x", inDiff: true, flags: {} },
				],
				metrics: {},
				directions: [],
			}),
		},
		additions({ "src/a.ts": 3 }),
	);
	const text = await section("cited");
	assert.match(text, /\[L2\] — kept/u);
	assert.doesNotMatch(text, /invented/u);
	assert.match(
		text,
		/^- 1 hint\(s\) on changed lines are not shown: their file and line are not in the change\./mu,
	);
});

void test("a script has no environment and cannot read outside the workspace, write, or start a program", async () => {
	const probe = `
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
const attempt = (action) => { try { action(); return "allowed"; } catch (error) { return error.code ?? "denied"; } };
export default () => ({
	hints: [],
	metrics: { environment: Object.keys(process.env).length },
	directions: [
		"read " + attempt(() => readFileSync("/etc/hostname")),
		"write " + attempt(() => writeFileSync("${path.join(tmpdir(), "precompute-escape")}", "x")),
		"program " + attempt(() => execFileSync("cat", ["/proc/1/environ"])),
	],
});
`;
	const { output, section } = await run({ probe });
	assert.equal(
		await section("probe"),
		"- read ERR_ACCESS_DENIED\n- write ERR_ACCESS_DENIED\n- program ERR_ACCESS_DENIED\n",
	);
	const written: unknown = JSON.parse(await readFile(path.join(output, "probe.json"), "utf8"));
	assert.ok(typeof written === "object" && written !== null);
	assert.deepEqual(Reflect.get(written, "metrics"), { environment: 0 });
});

void test("a script that never returns loses only its own practice, and is told apart from one that fails", async () => {
	const { section, json } = await run(
		{
			spin: "export default () => { for (;;) {} };\n",
			throws: 'export default () => { throw new Error("broken"); };\n',
			// A kill that the runner did not send, as the kernel sends when memory runs out.
			killed: 'export default () => process.kill(process.pid, "SIGKILL");\n',
			quick: script({ hints: [], metrics: {}, directions: ["finished"] }),
		},
		undefined,
		// The deadline includes starting the script's process, which a busy machine can take a second for.
		["--timeout", "3000"],
	);
	assert.match(await section("spin"), /Script failed: Timeout after \d+ms/u);
	// Neither said what it is before it ended, so the models it uses are not known.
	const spin = await json("spin");
	assert.partialDeepStrictEqual(spin, { contract: "unknown", status: "timeout" });
	assert.match(String(spin.error), /^Timeout after \d+ms$/u);
	assert.partialDeepStrictEqual(await json("throws"), {
		contract: "unknown",
		status: "error",
		error: "broken",
	});
	assert.partialDeepStrictEqual(await json("killed"), {
		contract: "unknown",
		status: "error",
		error: "the script's process was killed by SIGKILL",
	});
	assert.equal(await section("quick"), "- finished\n");
	const quick = await json("quick");
	assert.equal(quick.error, undefined);
});

void test("a large result or error reaches the runner whole, and the result keeps the error up to its bound as well-formed text", async () => {
	const { json } = await run({
		large:
			'export default () => ({ hints: [], metrics: {}, directions: ["d".repeat(1_000_000)] });\n',
		long: 'export default () => { throw new Error("x".repeat(1_000_000)); };\n',
		lone: 'export default () => { throw new Error("\\uD800"); };\n',
	});
	assert.partialDeepStrictEqual(await json("large"), { status: "ok" });
	assert.partialDeepStrictEqual(await json("long"), {
		status: "error",
		error: "x".repeat(MAX_ERROR_CHARS),
	});
	// A lone surrogate, as a cut through a surrogate pair leaves one, is stored as U+FFFD.
	assert.partialDeepStrictEqual(await json("lone"), { status: "error", error: "�" });
});

void test("the stage's totals count positional hints and definition leads apart", async () => {
	const { output } = await run(
		{
			hints: script({
				hints: [
					{ file: "src/a.ts", line: 1, pattern: "p", context: "x", inDiff: true, flags: {} },
					{ file: "notes.json", line: 0, pattern: "p", context: "x", inDiff: false, flags: {} },
				],
				metrics: {},
				directions: [],
			}),
			leads: `export default {
	meta: { kinds: { "added-line": "A line that the change adds." } },
	run: async () => ({ leads: [{ at: { change: "src/a.ts", line: 1 }, kind: "added-line" }] }),
};
`,
			throws: 'export default () => { throw new Error("broken"); };\n',
		},
		additions({ "src/a.ts": 1 }),
	);
	const timing: unknown = JSON.parse(await readFile(path.join(output, ".timing.json"), "utf8"));
	assert.partialDeepStrictEqual(timing, {
		practices: 3,
		positionalHints: 2,
		inDiffHints: 1,
		leads: 1,
		errors: 1,
	});
});

void test("a definition script that never returns is stopped at its deadline", async () => {
	const { section, json } = await run(
		{ spin: "export default { meta: { kinds: {} }, run: () => { for (;;) {} } };\n" },
		undefined,
		["--stage-ms", "4000"],
	);
	assert.partialDeepStrictEqual(await json("spin"), {
		contract: "definition",
		status: "timeout",
		models: {},
	});
	assert.match(
		await section("spin"),
		/\*\*Script failed\.\*\*.*- Script failed: Timeout after \d+ms/su,
	);
});

void test("a staged file whose name is not a practice slug does not run", async () => {
	const { output } = await run({
		Not_A_Slug: script({ hints: [], metrics: {}, directions: ["ran"] }),
		kept: script({ hints: [], metrics: {}, directions: ["ran"] }),
	});
	assert.equal(existsSync(path.join(output, "Not_A_Slug.json")), false);
	assert.equal(existsSync(path.join(output, "kept.json")), true);
});

void test("a practice that would start after the stage deadline does not run, and ended at the deadline", async () => {
	const { section, json } = await run(
		{ late: script({ hints: [], metrics: {}, directions: ["ran"] }) },
		undefined,
		["--stage-ms", "1"],
	);
	assert.match(
		await section("late"),
		/Script failed: the stage deadline passed before this practice started/u,
	);
	assert.partialDeepStrictEqual(await json("late"), { contract: "unknown", status: "timeout" });
});

void test("a script searches the workspace through the runner, which alone may start grep", async () => {
	const grepModule = path.join(import.meta.dirname, "lib", "grep.ts");
	const { root, args, section } = await stage({
		search: `
import { grep } from ${JSON.stringify(grepModule)};
export default async (repo) => {
	const matches = await grep("needle", repo, { glob: "*.txt" });
	return { hints: [], metrics: {}, directions: matches.map((m) => m.file + ":" + m.line) };
};
`,
	});
	await writeFile(path.join(root, "notes.txt"), "hay\nneedle\n");
	const { status, stderr } = spawnSync(process.execPath, args, { encoding: "utf8" });
	assert.equal(status, 0, stderr);
	// grep reports a path relative to the folder it searched.
	assert.equal(await section("search"), "- notes.txt:2\n");
});

void test("a practice that finished keeps its section when the stage is stopped", async () => {
	const { output, args } = await stage({
		quick: script({ hints: [], metrics: {}, directions: ["finished"] }),
		spin: "export default () => { for (;;) {} };\n",
	});
	const stopped = spawn(process.execPath, [...args, "--timeout", "60000"], {
		stdio: ["ignore", "ignore", "pipe"],
		detached: true,
	});
	// The runner logs a practice after it wrote the practice's files.
	for await (const line of createInterface({ input: stopped.stderr })) {
		if (line.includes(" quick ")) {
			break;
		}
	}
	process.kill(-Number(stopped.pid), "SIGKILL");
	assert.equal(await readFile(path.join(output, "quick.md"), "utf8"), "- finished\n");
	assert.equal(existsSync(path.join(output, ".complete")), false);
});
