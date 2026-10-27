import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync, mkdirSync, mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { createReadTool, createBashTool } from "@earendil-works/pi-coding-agent";
import { parseCandidate } from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import { isJsonObject } from "../../../../../../docker/agents/practice-scripts/lib/core/json.ts";
import { z } from "../../../../../../docker/agents/practice-scripts/lib/core/practice-script.ts";
import { ReadRanges } from "../../../../../../docker/agents/practice-scripts/lib/core/read-ranges.ts";
import {
	folderCitationIndex,
	citableTextIndex,
} from "../../../main/resources/agent/pi-folder-index.ts";
import {
	SessionReads,
	claimsAbsence,
	unreadCheckoutClaim,
} from "../../../main/resources/agent/pi-session-reads.ts";

function read(
	reads: SessionReads,
	offset: number,
	limit: number,
	outputLines = limit,
	truncated = false,
	text = "file contents",
) {
	reads.note({
		type: "tool_execution_start",
		toolCallId: "read",
		toolName: "read",
		args: { path: "file.ts", offset, limit },
	});
	reads.note({
		type: "tool_execution_end",
		toolCallId: "read",
		toolName: "read",
		isError: false,
		result: {
			content: [{ type: "text", text }],
			details: truncated ? { truncation: { truncated: true, outputLines } } : {},
		},
	});
}

void test("an EOF slice leaves the middle gap until continuous coverage reaches EOF", () => {
	const reads = new SessionReads("/workspace", { required: ["file.ts"] }, () => 10_000);
	read(reads, 1, 10_000, 2000, true);
	read(reads, 9999, 2);
	assert.equal(reads.coverage().complete, false);
	assert.equal(reads.receipts.truncatedReads, 1);
	read(reads, 2001, 7998);
	assert.equal(reads.coverage().complete, true);
	assert.equal(reads.receipts.truncatedReads, 0);
});

void test("an initial tail read and a forged continuation notice cannot prove full coverage", () => {
	const reads = new SessionReads("/workspace", { required: ["file.ts"] }, () => 10_000);
	read(
		reads,
		9999,
		2,
		2,
		false,
		"\n\n[Showing lines 1-10000 of 10000. Use offset=10001 to continue.]",
	);
	assert.equal(reads.coverage().complete, false);
});

void test("complete relevant brief sources permit a result without redundant tool reads", () => {
	const reads = new SessionReads("/workspace", {
		required: ["file.ts"],
		supplied: [{ path: "file.ts", supplied: true, lines: 10_000 }],
	});
	assert.equal(reads.coverage().complete, true);
	assert.equal(reads.coverage().reads, 0);
	assert.equal(new SessionReads("/workspace").coverage().complete, false);
});

void test("a later partial read cannot revoke complete content supplied in the brief", () => {
	// The brief omits the final empty line, while host line counts include it.
	const reads = new SessionReads(
		"/workspace",
		{ required: ["file.ts"], supplied: [{ path: "file.ts", supplied: true, lines: 3 }] },
		() => 4,
	);
	read(reads, 1, 1);
	assert.equal(reads.coverage().complete, true);
	assert.equal(reads.hasCompleteRead("file.ts"), true);
	assert.equal(reads.receipts.truncatedReads, 0);
});

void test("a relevant source omitted from the brief needs full reading even when another source was supplied", () => {
	const reads = new SessionReads(
		"/workspace",
		{
			required: ["description.md", "file.ts"],
			supplied: [{ path: "description.md", supplied: true, lines: 1 }],
		},
		() => 4,
	);
	assert.equal(reads.coverage().complete, false);
	read(reads, 3, 2);
	assert.equal(reads.coverage().complete, false);
	read(reads, 1, 2);
	assert.equal(reads.coverage().complete, true);
});

void test("absolute and relative paths cover the same pinned file", () => {
	const reads = new SessionReads("/workspace", { required: ["file.ts"] }, () => 1);
	reads.note({
		type: "tool_execution_start",
		toolCallId: "r",
		toolName: "read",
		args: { path: "/workspace/file.ts" },
	});
	reads.note({
		type: "tool_execution_end",
		toolCallId: "r",
		toolName: "read",
		isError: false,
		result: { content: [{ type: "text", text: "content" }], details: {} },
	});
	assert.equal(reads.coverage().complete, true);
});

for (const isError of [false, true]) {
	void test(`an ambiguous shell command cannot erase supplied coverage when isError=${isError}`, () => {
		const reads = new SessionReads("/workspace", {
			required: ["file.ts"],
			supplied: [{ path: "file.ts", supplied: true, lines: 1 }],
		});
		bash(reads, "rg pattern file.ts | head", "one hit", false, isError ? 2 : 0);
		assert.equal(reads.coverage().complete, true);
		assert.equal(reads.receipts.failedReads, isError ? 1 : 0);
		assert.equal(reads.coverage().failedReads, 0);
	});
}

const MET_RESULT = {
	outcome: "MET",
	summary: "Criterion met",
	explanation: "The described behavior meets the criterion.",
	citations: [
		{
			sourceKind: "scm.pull-request.core",
			sourcePath: "record.md",
			path: "record.md",
			startLine: 1,
			endLine: 1,
			quote: "work",
		},
	],
	evidence: {
		search: {
			consulted: ["scm.pull-request.core"],
			lookedFor: "criterion",
			boundary: "the item",
		},
	},
};

void test("the candidate contract refuses MET over missing brief material", () => {
	const reads = new SessionReads("/workspace", { required: ["file.ts"] });
	const parsed = parseCandidate({ ...MET_RESULT, coverage: reads.coverage() });
	assert.equal(parsed.ok, false);
	assert.equal(parsed.reasonCode, "met_coverage_incomplete");
});

void test("shared unknown-coverage proof admits a qualified outcome and refuses MET", () => {
	const candidate: unknown = JSON.parse(
		readFileSync(
			new URL("../practice-results/host-coverage-unknown.json", import.meta.url),
			"utf8",
		),
	);
	assert.ok(isJsonObject(candidate));
	const qualified = parseCandidate(candidate);
	assert.ok(qualified.ok, qualified.ok ? "" : qualified.message);
	assert.ok(isJsonObject(candidate.evidence));
	delete candidate.evidence.openQuestions;
	const met = parseCandidate({ ...candidate, outcome: "MET" });
	assert.ok(!met.ok);
	assert.equal(met.reasonCode, "met_coverage_incomplete");
});

void test("discovery and search alone do not imply that relevant content was reviewed", () => {
	for (const toolName of ["grep", "find", "ls"]) {
		const reads = new SessionReads("/workspace");
		reads.note({ type: "tool_execution_start", toolCallId: "r", toolName, args: {} });
		reads.note({
			type: "tool_execution_end",
			toolCallId: "r",
			toolName,
			isError: false,
			result: { content: [{ type: "text", text: "no matches" }], details: {} },
		});
		assert.equal(reads.coverage().complete, false, toolName);
	}
});

void test("a required source stays incomplete while its read is pending", () => {
	const reads = new SessionReads("/workspace", {
		required: ["file.ts", "other.ts"],
		supplied: [{ path: "file.ts", supplied: true, lines: 1 }],
	});
	reads.note({
		type: "tool_execution_start",
		toolCallId: "r",
		toolName: "read",
		args: { path: "other.ts" },
	});
	assert.equal(reads.coverage().complete, false);
});

void test("native SDK pagination settles coverage only after the missing pages are read", async (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "native-read-coverage-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeFileSync(
		path.join(root, "file.ts"),
		Array.from({ length: 10_000 }, (_, i) => `line ${i + 1}`).join("\n"),
	);
	const reads = new SessionReads(root, { required: ["file.ts"] });
	const tool = createReadTool(root);
	const inspect = async (offset: number, limit?: number) => {
		const args = { path: "file.ts", offset, ...(limit === undefined ? {} : { limit }) };
		reads.note({ type: "tool_execution_start", toolCallId: "read", toolName: "read", args });
		const result = await tool.execute("read", args);
		reads.note({
			type: "tool_execution_end",
			toolCallId: "read",
			toolName: "read",
			result,
			isError: false,
		});
	};
	await inspect(1);
	await inspect(9999);
	assert.equal(reads.coverage().complete, false);
	for (const offset of [2001, 4001, 6001, 8001]) {
		await inspect(offset, 2000);
	}
	assert.equal(reads.coverage().complete, true);
});

void test("Java-captured record aliases and extracted strings share complete parent coverage", () => {
	const fixture = z
		.object({ contents: z.record(z.string(), z.string()), manifest: z.unknown() })
		.parse(
			JSON.parse(readFileSync(new URL("record-coverage-capture.json", import.meta.url), "utf8")),
		);
	const { sourceFileSources } = folderCitationIndex(fixture.manifest);
	const texts = citableTextIndex(fixture.manifest);
	const required = [...sourceFileSources.keys()];
	const supplied = new SessionReads(
		"/workspace",
		{
			required,
			supplied: [{ path: "evidence/metadata.json", supplied: true, lines: 1 }],
		},
		undefined,
		texts,
	);
	assert.equal(supplied.coverage().complete, true);
	const reads = new SessionReads(
		"/workspace",
		{ required },
		(target) => fixture.contents[target]?.split("\n").length ?? assert.fail(target),
		texts,
	);
	assert.equal(reads.coverage().complete, false, "omitted parent content stays unread");
	const record = (offset: number, limit: number, truncated: boolean) => {
		reads.note({
			type: "tool_execution_start",
			toolName: "read",
			toolCallId: "record",
			args: { path: "evidence/metadata-alias.json", offset, limit },
		});
		reads.note({
			type: "tool_execution_end",
			toolName: "read",
			toolCallId: "record",
			isError: false,
			result: {
				content: [{ type: "text", text: "captured record" }],
				details: truncated ? { truncation: { truncated: true, outputLines: 0 } } : {},
			},
		});
	};
	record(1, 1, true);
	assert.equal(
		reads.coverage().complete,
		false,
		"a truncated parent does not cover its extracted copies",
	);
	record(1, 1, false);
	assert.equal(
		reads.coverage().complete,
		true,
		"a complete alias read covers the same canonical record and its fields",
	);
});

void test("change consultation belongs only to the session that received or read it", () => {
	const supplied = new SessionReads("/workspace", {
		required: ["file.ts"],
		supplied: [{ path: "file.ts", supplied: true, lines: 10 }],
	});
	const reader = new SessionReads("/workspace", { required: ["file.ts"] }, () => 10);
	const sibling = new SessionReads("/workspace", { required: ["file.ts"] }, () => 10);
	assert.equal(supplied.hasRead("file.ts"), true);
	assert.equal(reader.hasRead("file.ts"), false);
	read(reader, 1, 1);
	assert.equal(reader.hasRead("file.ts"), true);
	assert.equal(reader.hasRead("other.ts"), false);
	assert.equal(sibling.hasRead("file.ts"), false);
});

function bash(reads: SessionReads, command: string, text: string, cut = false, exit = 0) {
	reads.note({
		type: "tool_execution_start",
		toolCallId: "bash",
		toolName: "bash",
		args: { command },
	});
	reads.note({
		type: "tool_execution_end",
		toolCallId: "bash",
		toolName: "bash",
		isError: exit !== 0,
		result: {
			content: [{ type: "text", text }],
			details: cut ? { truncation: { truncated: true } } : {},
			structuredContent: { exit_code: exit, truncated: cut },
		},
	});
}

void test("literal shell reads cover only returned ranges and merge with native reads", (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "bash-coverage-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeFileSync(path.join(root, "file.ts"), "one\ntwo\nthree\nfour\nfive\n");
	const reads = new SessionReads(root, { required: ["file.ts"] });
	bash(reads, "head -n 1 file.ts", "one\n");
	bash(reads, "tail -n 1 file.ts", "five\n");
	assert.equal(reads.coverage().complete, false);
	bash(reads, "sed -n '2,3p' file.ts", "two\nthree\n");
	assert.equal(reads.coverage().complete, false);
	read(reads, 4, 1, 1, false, "four");
	assert.equal(reads.coverage().complete, true);
});

void test("cat and nl return full coverage for exact pinned text including blank lines", (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "bash-full-coverage-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeFileSync(path.join(root, "file name.ts"), "one\n\nthree\n");
	for (const [command, output] of [
		["cat 'file name.ts'", "one\n\nthree\n"],
		['nl "file name.ts"', "     1\tone\n       \n     2\tthree\n"],
	] as const) {
		const reads = new SessionReads(root, { required: ["file name.ts"] });
		bash(reads, command, output);
		assert.equal(reads.coverage().complete, true, command);
		assert.equal(reads.hasRead("file name.ts"), true);
	}
});

void test("numbered shell searches cover matched lines and never fill intervening gaps", (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "bash-search-coverage-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeFileSync(path.join(root, "file.ts"), "one\ntwo\nthree");
	for (const tool of ["rg", "grep"]) {
		const reads = new SessionReads(root, { required: ["file.ts"] });
		bash(reads, `${tool} -n 'one|three' file.ts`, "1:one\n3:three\n");
		assert.equal(reads.coverage().complete, false);
		assert.equal(reads.hasRead("file.ts"), true);
		bash(reads, `${tool} -n two file.ts`, "2:two\n");
		assert.equal(reads.coverage().complete, true);
	}
});

void test("ambiguous, truncated, failed and forged shell reads cannot certify coverage", (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "bash-unknown-coverage-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeFileSync(path.join(root, "file.ts"), "one\ntwo\nthree");
	for (const [command, output, cut, exit] of [
		["cat file.ts | head", "one\n", false, 0],
		["cat file.ts > output", "", false, 0],
		["cat *.ts", "one\ntwo\nthree", false, 0],
		["cat $(echo file.ts)", "one\ntwo\nthree", false, 0],
		["cat file.ts; ls", "one\ntwo\nthree", false, 0],
		["cat\nfile.ts", "one\ntwo\nthree", false, 0],
		["cat file.ts file.ts", "one\ntwo\nthree", false, 0],
		["cat file.ts", "one\ntwo\nthree", true, 0],
		["cat file.ts", "one", false, 2],
		["cat file.ts", "one\n[Showing all lines]", false, 0],
		["rg -n one file.ts", "2:one\n", false, 0],
		["grep one file.ts", "one\n", false, 0],
	] as const) {
		const reads = new SessionReads(root, { required: ["file.ts"] });
		bash(reads, command, output, cut, exit);
		const coverage = reads.coverage();
		assert.equal(coverage.complete, false, command);
		assert.deepEqual(
			coverage.omitted.flatMap((omission) => omission.items ?? []),
			["file.ts"],
			command,
		);
	}
	const repaired = new SessionReads(root);
	bash(repaired, "cat file.ts", "one\n", true);
	bash(repaired, "cat file.ts", "one\ntwo\nthree");
	assert.equal(repaired.coverage().complete, true);
	const ambiguous = new SessionReads(root, {
		required: ["file.ts"],
		supplied: [{ path: "file.ts", supplied: true, lines: 3 }],
	});
	bash(ambiguous, "cat file.ts | head", "one\n");
	assert.equal(
		ambiguous.coverage().complete,
		true,
		"an ambiguous command does not erase already supplied content",
	);
});

void test("an empty numbered search is neither a failed read nor complete file coverage", (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "bash-empty-search-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeFileSync(path.join(root, "file.ts"), "one\n");
	const reads = new SessionReads(root);
	bash(reads, "rg -n absent file.ts", "(no output)\n\nCommand exited with code 1", false, 1);
	assert.equal(reads.coverage().complete, false);
	assert.equal(reads.coverage().failedReads, 0);
});

void test("native bash output receives receipts for complete single-file reads and numbered searches", async (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "native-bash-coverage-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const tool = createBashTool(root);
	for (const { source, commands } of [
		{
			// Native nl differs across platforms on an unterminated last line; use a POSIX text file.
			source: "one\ntwo\nthree\n",
			commands: [
				"cat file.ts",
				"head -n 3 file.ts",
				"tail -n 3 file.ts",
				"sed -n '1,3p' file.ts",
				"nl file.ts",
			],
		},
		{
			// A numbered search proves only its matched lines, not a trailing empty logical line.
			source: "one\ntwo\nthree",
			commands: [
				// The review sandbox ships ripgrep; a host without it still covers the other forms.
				...(spawnSync("rg", ["--version"]).status === 0 ? ["rg -n '.' file.ts"] : []),
				"grep -n '.' file.ts",
			],
		},
	]) {
		writeFileSync(path.join(root, "file.ts"), source);
		for (const command of commands) {
			const reads = new SessionReads(root, { required: ["file.ts"] });
			reads.note({
				type: "tool_execution_start",
				toolCallId: "native",
				toolName: "bash",
				args: { command },
			});
			const result = await tool.execute("native", { command });
			reads.note({
				type: "tool_execution_end",
				toolCallId: "native",
				toolName: "bash",
				result,
				isError: false,
			});
			assert.equal(reads.coverage().complete, true, `${command}: ${JSON.stringify(result)}`);
		}
	}
});

for (const command of [
	"ls",
	"ls context/ && ls work/ 2>/dev/null",
	"find . -name '*.md' | head -10",
	"git -C repos/reviewed log --oneline -5",
	"git rev-parse HEAD",
	"pwd",
	"cat .gitignore 2>/dev/null",
	"echo ---; ls; echo ---",
]) {
	void test(`exploration does not block MET over complete required content: ${command}`, () => {
		const reads = new SessionReads("/workspace", {
			required: ["file.ts"],
			supplied: [{ path: "file.ts", supplied: true, lines: 1 }],
		});
		bash(reads, command, "discovery output", true, command.startsWith("cat") ? 1 : 0);
		const coverage = reads.coverage();
		assert.equal(coverage.complete, true);
		assert.equal(coverage.reads, 1);
		assert.equal(coverage.truncatedReads, 0);
		assert.equal(coverage.failedReads, 0);
		assert.ok(!("omitted" in coverage));
	});
}

void test("a failed native optional read leaves complete coverage, but a required absent file blocks", () => {
	for (const required of [false, true]) {
		const reads = new SessionReads("/workspace", {
			required: ["file.ts", ...(required ? [".gitignore"] : [])],
			supplied: [{ path: "file.ts", supplied: true, lines: 1 }],
		});
		reads.note({
			type: "tool_execution_start",
			toolCallId: "missing",
			toolName: "read",
			args: { path: ".gitignore" },
		});
		reads.note({
			type: "tool_execution_end",
			toolCallId: "missing",
			toolName: "read",
			isError: true,
			result: { content: [{ type: "text", text: "ENOENT: .gitignore" }] },
		});
		assert.equal(reads.receipts.failedReads, 1);
		const coverage = reads.coverage();
		assert.equal(coverage.complete, !required);
		assert.equal(coverage.failedReads, required ? 1 : 0);
		if (!coverage.complete) {
			assert.deepEqual(coverage.omitted[0].items, [".gitignore"]);
		}
	}
});

void test("a truncated INDEX is incidental until cited, while a required diff always blocks", (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "scoped-read-coverage-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeFileSync(path.join(root, "INDEX.json"), "one\ntwo\nthree");
	writeFileSync(path.join(root, "diff.patch"), "first\nsecond\nthird");
	const reads = new SessionReads(root, { required: ["diff.patch"] });
	bash(reads, "cat INDEX.json", "one\n", true);
	bash(reads, "cat diff.patch", "first\n", true);
	let coverage = reads.coverage();
	assert.equal(coverage.complete, false);
	assert.deepEqual(
		coverage.omitted.flatMap((omission) => omission.items ?? []),
		["diff.patch"],
	);
	bash(reads, "cat diff.patch", "first\nsecond\nthird");
	assert.equal(reads.coverage().complete, true);
	assert.equal(
		reads.receipts.truncatedReads,
		1,
		"session diagnostics retain the incidental truncation",
	);
	coverage = reads.coverage(undefined, undefined, ["INDEX.json"]);
	assert.equal(coverage.complete, false);
	assert.deepEqual(
		coverage.omitted.flatMap((omission) => omission.items ?? []),
		["INDEX.json"],
	);
	bash(reads, "cat INDEX.json", "one\ntwo\nthree");
	assert.equal(reads.coverage(undefined, undefined, ["INDEX.json"]).complete, true);
});

void test("a required head window is settled by a full read, including harmless stderr redirection", (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "head-read-coverage-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeFileSync(path.join(root, "file.ts"), "one\ntwo\nthree");
	const reads = new SessionReads(root, { required: ["file.ts"] });
	bash(reads, "head -n 1 file.ts 2>/dev/null", "one\n");
	assert.equal(reads.coverage().complete, false);
	bash(reads, "cat file.ts 2>/dev/null", "one\ntwo\nthree");
	assert.equal(reads.coverage().complete, true);
});

const DIFF = "work/change/diff.patch";

/** A synthetic change view whose [L<n>] markers differ from its physical file lines. */
function writeDiff(root: string, lines: number): string {
	const text = [
		"diff --git a/src/Synthetic.ts b/src/Synthetic.ts",
		"--- a/src/Synthetic.ts",
		"+++ b/src/Synthetic.ts",
		`@@ -300,0 +300,${lines - 4} @@`,
		...Array.from(
			{ length: lines - 4 },
			(_, i) => `[L${i + 300}] + const value${i} = compute(${i}, "synthetic padding");`,
		),
	].join("\n");
	mkdirSync(path.join(root, "work/change"), { recursive: true });
	writeFileSync(path.join(root, DIFF), text);
	return text;
}

/** One call to the real SDK read tool, observed as the runner observes it. */
async function nativeRead(
	reads: SessionReads,
	root: string,
	args: { path: string; offset?: number; limit?: number },
): Promise<string> {
	reads.note({ type: "tool_execution_start", toolCallId: "read", toolName: "read", args });
	const result = await createReadTool(root).execute("read", args);
	reads.note({
		type: "tool_execution_end",
		toolCallId: "read",
		toolName: "read",
		result,
		isError: false,
	});
	return result.content.flatMap((part) => (part.type === "text" ? [part.text] : [])).join("\n");
}

const readCall = z.object({
	path: z.string(),
	offset: z.number().int().optional(),
	limit: z.number().int().optional(),
});

/** Make each read call that a repair names, and continue each cut read as the repair says. */
async function follow(reads: SessionReads, root: string, repair: string): Promise<number> {
	let calls = 0;
	for (const { groups } of repair.matchAll(/read\((?<json>\{[^)]*\})\)/gu)) {
		let args = readCall.parse(JSON.parse(groups?.json ?? ""));
		for (;;) {
			calls += 1;
			const next = Number(
				/Use offset=(?<offset>\d+) to continue/u.exec(await nativeRead(reads, root, args))?.groups
					?.offset,
			);
			if (args.offset === undefined || args.limit === undefined || !Number.isSafeInteger(next)) {
				break;
			}
			const end = args.offset + args.limit - 1;
			if (next > end) {
				break;
			}
			args = { path: args.path, offset: next, limit: end - next + 1 };
		}
	}
	return calls;
}

const call = (offset: number, limit: number) =>
	`read(${JSON.stringify({ path: DIFF, offset, limit })})`;
const admit = (reads: SessionReads) =>
	parseCandidate({ ...MET_RESULT, coverage: reads.coverage() });

void test("read ranges list the unread lines between merged extents up to the last line", () => {
	const ranges = new ReadRanges();
	assert.equal(ranges.gaps("file.ts"), undefined);
	ranges.expect("file.ts");
	assert.equal(ranges.gaps("file.ts"), undefined, "the length is unknown until a read counts it");
	ranges.add("file.ts", 5, 7, 20);
	ranges.add("file.ts", 8, 9);
	ranges.add("file.ts", 15, 30);
	assert.deepEqual(ranges.gaps("file.ts"), [
		{ start: 1, end: 4 },
		{ start: 10, end: 14 },
	]);
	ranges.add("file.ts", 1, 14);
	assert.deepEqual(ranges.gaps("file.ts"), []);
	assert.equal(ranges.complete("file.ts"), true);
});

void test("a MET refused over a missing head names those file lines, and reading exactly them admits it", async (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "repair-head-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeDiff(root, 2371);
	const session = async () => {
		const reads = new SessionReads(root, { required: [DIFF] });
		await follow(reads, root, call(58, 2314));
		return reads;
	};
	const reads = await session();
	assert.deepEqual(reads.remaining(DIFF), [{ start: 1, end: 57 }]);
	assert.equal(admit(reads).ok, false);
	const repair = reads.repair([DIFF]);
	assert.ok(repair.includes(call(1, 57)), repair);
	assert.equal([...repair.matchAll(/read\(/gu)].length, 1, repair);
	assert.match(repair, /physical lines of the file, not the \[L<n>\] markers/u);
	assert.doesNotMatch(repair, /Bash output/u);
	assert.equal(await follow(reads, root, repair), 1);
	const admitted = admit(reads);
	assert.ok(admitted.ok, admitted.ok ? "" : admitted.message);
	assert.equal(reads.repair([DIFF]), "");

	const short = await session();
	await nativeRead(short, root, { path: DIFF, offset: 1, limit: 56 });
	assert.equal(admit(short).ok, false, "one unread line still blocks MET");
	assert.ok(short.repair([DIFF]).includes(call(57, 1)), short.repair([DIFF]));
});

void test("a cut read is credited to its returned lines, and the named continuations settle the gap", async (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "repair-continuation-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeDiff(root, 2371);
	const reads = new SessionReads(root, { required: [DIFF] });
	await follow(reads, root, call(2343, 29));
	assert.ok(reads.repair([DIFF]).includes(call(1, 2342)), reads.repair([DIFF]));
	const first = await nativeRead(reads, root, { path: DIFF, offset: 1, limit: 2342 });
	const next = Number(
		/KB limit\)\. Use offset=(?<offset>\d+) to continue/u.exec(first)?.groups?.offset,
	);
	assert.ok(next > 1 && next < 2000, first.slice(-200));
	assert.deepEqual(reads.remaining(DIFF), [{ start: next, end: 2342 }]);
	assert.ok(reads.repair([DIFF]).includes(call(next, 2343 - next)), reads.repair([DIFF]));
	assert.equal(admit(reads).ok, false);
	assert.ok((await follow(reads, root, reads.repair([DIFF]))) > 1, "the byte limit cuts again");
	assert.ok(admit(reads).ok);
});

void test("uncounted shell output is explained only for the source it named, and a literal range read counts", (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "repair-bash-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const text = writeDiff(root, 2371);
	const reads = new SessionReads(root, { required: [DIFF] });
	bash(reads, "ls work/change | head", "diff.patch\n");
	assert.doesNotMatch(reads.repair([DIFF]), /Bash output/u);
	bash(reads, `grep -n value ${DIFF} | head -40`, "5:[L300] + const value0\n");
	assert.equal(admit(reads).ok, false);
	const repair = reads.repair([DIFF]);
	assert.ok(repair.includes(call(1, 2371)), repair);
	assert.match(repair, /Bash output from pipes, globs or compound commands is not counted/u);
	bash(reads, `sed -n '1,2371p' ${DIFF}`, text);
	assert.ok(admit(reads).ok);
});

void test("a result that decides nothing needs change content this session read, not piped shell output", async (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "repair-undecided-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	writeDiff(root, 2371);
	const reads = new SessionReads(root, { required: [DIFF] });
	assert.equal(reads.hasRead(DIFF), false);
	assert.equal(reads.bashNote([DIFF]), "");
	bash(reads, `cat ${DIFF} | head -50`, "diff --git\n");
	assert.equal(reads.hasRead(DIFF), false);
	assert.match(reads.bashNote([DIFF]), /not counted as reading/u);
	assert.equal(await follow(reads, root, `read(${JSON.stringify({ path: DIFF })})`), 1);
	assert.equal(reads.hasRead(DIFF), true);
});

void test("a repair never names a path outside the session root", () => {
	const reads = new SessionReads("/workspace", { required: [DIFF] }, () => 3);
	const repair = reads.repair([
		DIFF,
		"../outside/secret.ts",
		"/etc/passwd",
		"/workspace/../home/user/notes.md",
	]);
	assert.ok(repair.includes(call(1, 3)), repair);
	assert.doesNotMatch(repair, /secret|passwd|notes\.md|\.\.\//u);
	assert.equal(reads.repair(["../outside/secret.ts", "/etc/passwd"]), "");
});

const capturedTreeText = (stagedPath: string, sourcePath: string) => ({
	source: "scm.repository.tree",
	path: sourcePath,
	side: "NEW" as const,
	commit: null,
	stagedPath,
	sha256: "a".repeat(64),
});

void test("supplied and equivalent captured content leave nothing to repair", () => {
	const reads = new SessionReads(
		"/workspace",
		{
			required: [DIFF, "evidence/a.java", "evidence/b.java"],
			supplied: [
				{ path: DIFF, supplied: true, lines: 2371 },
				{ path: "evidence/a.java", supplied: true, lines: 3 },
			],
		},
		() => assert.fail("complete content needs no line count"),
		[
			capturedTreeText("evidence/a.java", "src/A.java"),
			capturedTreeText("evidence/b.java", "src/B.java"),
		],
	);
	assert.deepEqual(reads.remaining(DIFF), []);
	assert.deepEqual(reads.remaining("evidence/b.java"), []);
	assert.equal(reads.repair([DIFF, "evidence/b.java"]), "");
	assert.ok(admit(reads).ok);
});

void test("a line over the read tool byte limit is named as unreadable instead of a call that cannot succeed", async (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "repair-oversized-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	mkdirSync(path.join(root, "work"));
	writeFileSync(path.join(root, "work/bundle.js"), `${"x".repeat(60_000)}\nshort`);
	const reads = new SessionReads(root, { required: ["work/bundle.js"] });
	await nativeRead(reads, root, { path: "work/bundle.js" });
	assert.equal(admit(reads).ok, false);
	const repair = reads.repair(["work/bundle.js"]);
	assert.match(
		repair,
		/file line 1 of work\/bundle\.js alone exceeds the read tool's output limit, so no read can cover work\/bundle\.js and no MET result that needs it can be admitted/u,
	);
	assert.doesNotMatch(repair, /read\(|\d+ ?KB/u);
});

void test("a cited fully supplied source gets credit without requiring unrelated brief omissions", () => {
	const canonical = "context/linked_work_items/3.md";
	const omitted = "context/general_comments.json";
	const reads = new SessionReads("/workspace", {
		required: ["context/metadata.json"],
		supplied: [
			{ path: "context/metadata.json", supplied: true, lines: 1 },
			{ path: canonical, supplied: true, lines: 14 },
			{ path: omitted, supplied: false, omitted: "brief-limit" },
		],
	});
	assert.equal(reads.hasRead(canonical), true);
	assert.equal(reads.hasCompleteRead(canonical), true);
	assert.deepEqual(reads.remaining(canonical), []);
	assert.equal(reads.repair([canonical]), "");
	assert.equal(reads.coverage(undefined, undefined, [canonical]).complete, true);
	assert.equal(reads.coverage().reads, 0);
	assert.equal(reads.coverage(undefined, undefined, [omitted]).complete, false);
	assert.equal(
		reads.coverage().complete,
		true,
		"a citation does not change later source obligations",
	);
});

for (const source of [
	{ path: "file.ts", supplied: false, lines: 4, omitted: "file-limit" as const },
	{ path: "file.ts", supplied: true },
]) {
	void test(`a cited source needs complete native reading without full supply metadata: ${JSON.stringify(source)}`, () => {
		const reads = new SessionReads("/workspace", { supplied: [source] }, () => 4);
		const coverage = () => reads.coverage(undefined, undefined, ["file.ts"]);
		assert.equal(reads.hasRead("file.ts"), false);
		assert.equal(coverage().complete, false);
		read(reads, 1, 1);
		assert.equal(coverage().complete, false);
		assert.deepEqual(reads.remaining("file.ts"), [{ start: 2, end: 4 }]);
		read(reads, 2, 3);
		assert.equal(coverage().complete, true);
	});
}

void test("supplied canonical content covers aliases only through the captured source identity", () => {
	const canonical = "context/linked_work_items/3.md";
	const alias = "context/citable-records/issue.md";
	const sameBytesOtherSource = "context/citable-records/other-source.md";
	const otherBytes = "context/citable-records/other-content.md";
	const noIdentity = "context/citable-records/unidentified.md";
	const linked = {
		source: "scm.linked-work-items",
		path: canonical,
		side: "NEW" as const,
		commit: null,
		stagedPath: canonical,
		sha256: "a".repeat(64),
	};
	const reads = new SessionReads(
		"/workspace",
		{ supplied: [{ path: canonical, supplied: true, lines: 14 }] },
		() => 14,
		[
			linked,
			{ ...linked, stagedPath: alias },
			{ ...linked, source: "scm.pull-request.core", stagedPath: sameBytesOtherSource },
			{ ...linked, sha256: "b".repeat(64), stagedPath: otherBytes },
		],
	);
	assert.equal(reads.coverage(undefined, undefined, [alias]).complete, true);
	for (const target of [sameBytesOtherSource, otherBytes, noIdentity]) {
		assert.equal(reads.hasCompleteRead(target), false, target);
		assert.equal(reads.coverage(undefined, undefined, [target]).complete, false, target);
	}
});

const TREE = {
	root: "repos/reviewed",
	sourcePath: "repos/reviewed/.git/HEAD",
	version: "4154fb8c",
	complete: true,
};

function treeTool(
	reads: SessionReads,
	id: string,
	toolName: string,
	args: Record<string, unknown>,
	details: Record<string, unknown> = {},
) {
	// A call a codemode script makes arrives as its own tool event under the script's call.
	reads.note({
		type: "tool_execution_start",
		toolCallId: id,
		toolName,
		args,
		parentToolCallId: "codemode",
	});
	reads.note({
		type: "tool_execution_end",
		toolCallId: id,
		toolName,
		isError: false,
		result: { content: [{ type: "text", text: "No matches found" }], details },
	});
}

function checkout(): string {
	const root = mkdtempSync(path.join(tmpdir(), "session-tree-"));
	mkdirSync(path.join(root, "repos/reviewed/src"), { recursive: true });
	mkdirSync(path.join(root, "repos/reviewed/.git"), { recursive: true });
	writeFileSync(path.join(root, "repos/reviewed/src/UserController.java"), "class A {\n}\n");
	writeFileSync(path.join(root, "repos/reviewed/.git/config"), "[core]\n");
	return root;
}

void test("codemode's whole-checkout searches and complete reads leave receipts that prove them, searches first", () => {
	const root = checkout();
	try {
		const reads = new SessionReads(root, {}, undefined, [], TREE);
		treeTool(reads, "find", "find", { pattern: "*.java", path: "repos/reviewed" });
		treeTool(reads, "read", "read", { path: "repos/reviewed/src/UserController.java" });
		treeTool(reads, "git", "read", { path: "repos/reviewed/.git/config" });
		treeTool(reads, "grep", "grep", { pattern: "ControllerAdvice" });
		treeTool(reads, "rooted", "grep", { pattern: "ExceptionHandler", path: "repos/reviewed" });

		const { receipts } = reads.coverage();
		assert.deepEqual(
			receipts.map((receipt) => [receipt.status, receipt.path, receipt.pattern]),
			[
				["SEARCHED", ".", "ControllerAdvice"],
				["SEARCHED", ".", "ExceptionHandler"],
				["COMPLETE", "src/UserController.java", undefined],
			],
		);
		const fileRead = receipts[2];
		assert.ok(fileRead !== undefined);
		assert.equal(fileRead.sourcePath, TREE.sourcePath);
		assert.match(fileRead.contentDigest ?? "", /^[0-9a-f]{64}$/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a search that is cut, filtered, narrower than the checkout, outside it or a listing proves no search", () => {
	const root = checkout();
	try {
		const reads = new SessionReads(root, {}, undefined, [], TREE);
		treeTool(
			reads,
			"cut",
			"grep",
			{ pattern: "Advice", path: "repos/reviewed" },
			{ matchLimitReached: 100 },
		);
		treeTool(reads, "glob", "grep", { pattern: "Advice", glob: "*.md" });
		treeTool(reads, "narrow", "grep", { pattern: "Advice", path: "repos/reviewed/src" });
		treeTool(reads, "context", "grep", { pattern: "Advice", path: "context" });
		treeTool(reads, "find", "find", { pattern: "*Advice*", path: "repos/reviewed" });

		assert.deepEqual(reads.coverage().receipts, []);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a search of a checkout captured only in part proves no search", () => {
	const root = checkout();
	try {
		const reads = new SessionReads(root, {}, undefined, [], { ...TREE, complete: false });
		treeTool(reads, "grep", "grep", { pattern: "ControllerAdvice" });

		assert.deepEqual(reads.coverage().receipts, []);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

// The error-handling problem that #50's review recorded (review 2435ae89), with its stored basis.
const PR50_ERROR_HANDLING = {
	summary:
		"page parameter is unvalidated; negative or overflowing values throw an unhandled IndexOutOfBoundsException",
	explanation:
		"For a negative page, from is negative. Per the JDK contract, List.subList throws IndexOutOfBoundsException when fromIndex is negative. No code catches this and the reviewed checkout contains no @ControllerAdvice or @ExceptionHandler (grep over all 8 Java files found none), so invalid input produces an unhandled exception surfacing as a raw 500.",
	basis: { explanation: "The diff adds this unvalidated page parameter and the subList slicing." },
};
const PR50_VALIDATES_INPUTS = {
	summary: "Negative page param flows unclamped into subList and throws",
	explanation:
		'The added handler\'s only guard on `page` is `@RequestParam(defaultValue = "0")`. The pinned file has no @Min, clamp or other check on the path.',
	basis: { explanation: "The diff adds the page parameter." },
};

function hostCoverage(
	receipts: { status: "COMPLETE" | "SEARCHED"; path: string; pattern?: string }[] = [],
) {
	return {
		source: "host" as const,
		reads: 3,
		truncatedReads: 0,
		failedReads: 0,
		complete: true as const,
		receipts: receipts.map((receipt) => ({
			sourceKind: "scm.repository.tree",
			sourcePath: TREE.sourcePath,
			version: TREE.version,
			...receipt,
		})),
	};
}

const ASK = { checkout: { root: "repos/reviewed", complete: true }, askAboutWords: true };
const BOUNDARY = "the changed file only; no other files are in the change";

function notMet(
	problem: { summary: string; explanation: string; basis: { explanation: string } },
	consulted: string[],
) {
	return {
		outcome: "NOT_MET",
		summary: "The new page parameter is not validated",
		explanation: "A negative page reaches subList.",
		problems: [problem],
		evidence: { search: { consulted, lookedFor: "a guard on page", boundary: BOUNDARY } },
	};
}

void test("#50: a claim that the checkout lacks something goes back to the model until the checkout was searched", () => {
	const stored = notMet(PR50_ERROR_HANDLING, ["scm.pull-request.diff", "scm.repository.tree"]);
	const asked = unreadCheckoutClaim(stored, hostCoverage(), ASK);
	assert.match(
		asked?.text ?? "",
		/names scm\.repository\.tree as searched.*path "repos\/reviewed"/u,
	);
	assert.equal(asked?.byWords, false);
	// Reading UserController to its end, as the stored result says it did, is no search of the checkout.
	assert.notEqual(
		unreadCheckoutClaim(
			stored,
			hostCoverage([{ status: "COMPLETE", path: "src/main/java/UserController.java" }]),
			ASK,
		),
		undefined,
	);
	assert.equal(
		unreadCheckoutClaim(
			stored,
			hostCoverage([{ status: "SEARCHED", path: ".", pattern: "ControllerAdvice" }]),
			ASK,
		),
		undefined,
	);
});

void test("#50: leaving the tree out of the search does not hide words that say the repository lacks something, once", () => {
	const hidden = notMet(PR50_ERROR_HANDLING, ["scm.pull-request.diff"]);
	assert.equal(unreadCheckoutClaim(hidden, hostCoverage(), ASK)?.byWords, true);
	assert.equal(
		unreadCheckoutClaim(hidden, hostCoverage(), { ...ASK, askAboutWords: false }),
		undefined,
	);
	// What the cited file itself lacks is not a claim about the rest of the checkout.
	assert.equal(
		unreadCheckoutClaim(
			notMet(PR50_VALIDATES_INPUTS, ["scm.pull-request.diff"]),
			hostCoverage(),
			ASK,
		),
		undefined,
	);
	// A could-not-determine result says nothing about the work.
	assert.equal(
		unreadCheckoutClaim({ ...hidden, outcome: "COULD_NOT_DETERMINE" }, hostCoverage(), ASK),
		undefined,
	);
});

void test("without a checkout captured whole, the model is told to remove the claim, not to search", () => {
	const hidden = notMet(PR50_ERROR_HANDLING, ["scm.pull-request.diff"]);
	assert.match(
		unreadCheckoutClaim(hidden, hostCoverage(), {
			checkout: { complete: false },
			askAboutWords: true,
		})?.text ?? "",
		/no checkout to search\. Remove that claim/u,
	);
	assert.match(
		unreadCheckoutClaim(hidden, hostCoverage(), {
			checkout: { root: "repos/reviewed", complete: false },
			askAboutWords: true,
		})?.text ?? "",
		/captured only in part, so no search of it can show what the repository lacks\. Remove that claim/u,
	);
});

void test("an issue whose review reads no checkout keeps the words its Does not apply rests on", () => {
	const issue = {
		outcome: "DOES_NOT_APPLY",
		summary: "The issue is self-contained",
		explanation: "The body states its outcome directly.",
		evidence: {
			inapplicability: {
				consulted: ["issue.core"],
				prerequisite: "an appeal to context recorded somewhere else",
				ruledOutBy: "the body contains no appeal to context made elsewhere and no links",
			},
		},
	};
	assert.equal(unreadCheckoutClaim(issue, hostCoverage(), { askAboutWords: true }), undefined);
});

void test("#50: other wordings of the same claim, in any text the result shows, go back to the model", () => {
	const claims = [
		"No @ControllerAdvice or @ExceptionHandler exists in the repository.",
		"The reviewed checkout contains no @ControllerAdvice.",
		"Nothing in the project catches IndexOutOfBoundsException.",
		"The application defines no global exception handler.",
		"There is no exception handler anywhere in the code.",
		"None of the callers handle the null.",
		"Nothing else in the code base validates page.",
		"The codebase never validates page.",
	];
	for (const claim of claims) {
		const bounded = notMet(PR50_VALIDATES_INPUTS, ["scm.pull-request.diff"]);
		const { search } = bounded.evidence;
		for (const submission of [
			{ ...bounded, summary: claim },
			{ ...bounded, explanation: claim },
			{ ...bounded, problems: [{ ...PR50_VALIDATES_INPUTS, nextStep: claim }] },
			{ ...bounded, problems: [{ ...PR50_VALIDATES_INPUTS, basis: { explanation: claim } }] },
			{ ...bounded, evidence: { search: { ...search, boundary: claim } } },
			{ ...bounded, evidence: { search: { ...search, lookedFor: claim } } },
		]) {
			assert.equal(unreadCheckoutClaim(submission, hostCoverage(), ASK)?.byWords, true, claim);
		}
	}
});

void test("words bounded to the change, or stating what the review did not establish, are no claim", () => {
	for (const bounded of [
		"no test file or test hunk appears anywhere in the change",
		"no print-style or logger call is added anywhere in the diff",
		"no other files are in the change",
		"The change adds no diagnostic call in application code.",
		"the body contains no appeal to context made elsewhere and no links",
		"There is no check anywhere on its path that rules out this input.",
		"No code path in handle() returns 404.",
		"The checkout step in the workflow has no pinned version.",
		"This does not establish that unchanged settings elsewhere in the repository are sound.",
		"I did not inspect files outside the diff or establish that no other generated artifacts exist elsewhere in the repository.",
		"No test is added anywhere.",
		"The catch block never logs the error anywhere.",
		"No code in the handler catches the error.",
	]) {
		assert.equal(claimsAbsence(bounded), false, bounded);
	}
	// A limit of the review does not hide the claim beside it.
	for (const claim of [
		"I did not search beyond the diff, but no handler exists anywhere in the repository.",
		"page is not checked, and the checkout contains no @ControllerAdvice.",
	]) {
		assert.equal(claimsAbsence(claim), true, claim);
	}
});

void test("a bounded Met that names the tree for its selected lines is told how to name the diff instead", () => {
	// handles-errors-instead-of-swallowing-them asks a Met to record evidence.search over the selected pinned source.
	const met = {
		outcome: "MET",
		summary: "The added failure paths keep their error signal",
		explanation:
			"Both added catch blocks rethrow with context; the judgement covers the operators the diff adds.",
		evidence: {
			search: {
				consulted: ["scm.repository.tree"],
				lookedFor: "a swallowed failure in the added operators",
				boundary: "the selected lines only",
			},
		},
	};
	assert.match(
		unreadCheckoutClaim(met, hostCoverage(), ASK)?.text ?? "",
		/name the source that holds them, such as scm\.pull-request\.diff, in place of scm\.repository\.tree/u,
	);
	const diff = { ...met.evidence.search, consulted: ["scm.pull-request.diff"] };
	assert.equal(
		unreadCheckoutClaim({ ...met, evidence: { search: diff } }, hostCoverage(), ASK),
		undefined,
	);
});
