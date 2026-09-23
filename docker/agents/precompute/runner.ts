#!/usr/bin/env node
import { existsSync } from "node:fs";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";

import { globFilesSync } from "./lib/files.ts";

import { parseDiff } from "./lib/diff-parser.ts";
import { isJsonObject, isPracticeModule, parseFindings } from "./lib/practice-contract.ts";
import type { ArtifactMetadata, DiffFile, Hint, PracticeResult } from "./lib/types.ts";

const DEFAULT_OUTPUT_DIR = ".precompute";
const DEFAULT_TIMEOUT_MS = 15_000;

const { values } = parseArgs({
	args: process.argv.slice(2),
	options: {
		repo: { type: "string" },
		diff: { type: "string" },
		metadata: { type: "string" },
		context: { type: "string" },
		change: { type: "string" },
		practices: { type: "string" },
		output: { type: "string", default: DEFAULT_OUTPUT_DIR },
		timeout: { type: "string", default: String(DEFAULT_TIMEOUT_MS) },
	},
});

if (values.repo === undefined || values.repo === "") {
	console.error(
		"Usage: node runner.ts --repo <path> --diff <path> [--metadata <path>] [--context <dir>] [--change <dir>] [--output <dir>]",
	);
	process.exit(1);
}

const globalStart = Date.now();
const repoPath = values.repo;
const outputDir = values.output;
// A non-numeric or non-positive --timeout would make every race timer fire immediately and time out
// every practice on the spot, so an unusable value falls back to the default instead of silently
// disabling precompute.
const requestedTimeoutMs = Number.parseInt(values.timeout, 10);
const timeoutIsUsable = Number.isFinite(requestedTimeoutMs) && requestedTimeoutMs > 0;
if (!timeoutIsUsable) {
	console.error(`Ignoring unusable --timeout ${values.timeout}; using ${DEFAULT_TIMEOUT_MS}ms`);
}
const timeoutMs = timeoutIsUsable ? requestedTimeoutMs : DEFAULT_TIMEOUT_MS;
const contextDir = values.context ?? "";
const changeDir = values.change ?? "";

let diffFiles = new Map<string, DiffFile>();
if (values.diff !== undefined && values.diff !== "") {
	try {
		const diffContent = await readFile(values.diff, "utf8");
		diffFiles = parseDiff(diffContent);
		console.error(`Parsed diff: ${diffFiles.size} files`);
	} catch (error) {
		console.error(`Could not parse diff: ${String(error)}`);
	}
}

let metadata: ArtifactMetadata = {};
if (values.metadata !== undefined && values.metadata !== "") {
	try {
		const parsed: unknown = JSON.parse(await readFile(values.metadata, "utf8"));
		if (isJsonObject(parsed)) {
			metadata = parsed;
		} else {
			console.error(`Metadata ${values.metadata} is not a JSON object; scripts will see {}`);
		}
	} catch (error) {
		console.error(`Could not load metadata: ${String(error)}`);
	}
}

const practicesDir = values.practices ?? `${outputDir}/practices`;
const practiceModules: [string, string][] = [];

if (existsSync(practicesDir)) {
	for (const file of globFilesSync("*.ts", practicesDir)) {
		const slug = file.replace(/\.ts$/u, "");
		practiceModules.push([slug, `${practicesDir}/${file}`]);
	}
}

if (practiceModules.length === 0) {
	console.error("No practice scripts found. Exiting.");
	await mkdir(outputDir, { recursive: true });
	await writeFile(
		`${outputDir}/summary.md`,
		"# Precomputed Analysis\n\n> No practice scripts available.\n",
	);
	process.exit(0);
}

console.error(`Running ${practiceModules.length} practice analyzer(s)...`);

const tmpDir = `${outputDir}.tmp.${process.pid}`;
await rm(tmpDir, { recursive: true, force: true });
await mkdir(tmpDir, { recursive: true });

/** A practice script is foreign code (DB-stored data), so it can reject with a non-Error value. */
function messageOf(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}

/**
 * Reject an asynchronous practice that exceeds its budget. This cannot preempt synchronous work.
 */
async function withTimeout<T>(work: T | Promise<T>): Promise<T> {
	const timer = new AbortController();
	try {
		return await Promise.race([work, timeoutAfter(timeoutMs, timer.signal)]);
	} finally {
		timer.abort();
	}
}

async function timeoutAfter(ms: number, signal: AbortSignal): Promise<never> {
	await delay(ms, undefined, { signal });
	throw new Error(`Timeout after ${ms}ms`);
}

function validateResult(result: unknown, slug: string): PracticeResult {
	const findings = parseFindings(result, `Script ${slug}`);
	return {
		practice: slug,
		status: "ok",
		hints: findings.hints,
		metrics: findings.metrics,
		directions: findings.directions.slice(0, 10),
	};
}

const results = await Promise.allSettled(
	practiceModules.map(async ([slug, modulePath]) => {
		const start = Date.now();
		try {
			const mod: unknown = await import(pathToFileURL(modulePath).href);
			if (!isPracticeModule(mod)) {
				throw new Error(`Script ${slug} must export a default function`);
			}
			const rawResult: unknown = await withTimeout(
				mod.default(repoPath, diffFiles, metadata, contextDir, changeDir),
			);
			const result = validateResult(rawResult, slug);
			const elapsed = Date.now() - start;
			console.error(`  ok ${slug}: ${result.hints.length} hints (${elapsed}ms)`);
			return result;
		} catch (error) {
			const elapsed = Date.now() - start;
			const message = messageOf(error);
			console.error(`  FAIL ${slug}: ${message} (${elapsed}ms)`);
			return {
				practice: slug,
				status: "error" as const,
				hints: [],
				metrics: { error: 1 },
				directions: [`Script failed: ${message}`],
			} satisfies PracticeResult;
		}
	}),
);

const practiceResults: PracticeResult[] = results.map((r) =>
	r.status === "fulfilled"
		? r.value
		: {
				practice: "unknown",
				status: "error" as const,
				hints: [],
				metrics: { error: 1 },
				directions: ["Promise rejected"],
			},
);

for (const result of practiceResults) {
	await writeFile(`${tmpDir}/${result.practice}.json`, JSON.stringify(result, null, 2));
}

/** Record rows shown per practice before the rest is left to the practice's JSON file. */
const RECORD_ROWS = 20;
/** Changed-line rows shown in full per practice; above this, a sample and the JSON pointer. */
const IN_DIFF_ROWS = 10;
const IN_DIFF_SAMPLE = 5;
/**
 * The summary is inlined in the brief whole or withheld whole, so it stays under the brief's per-file
 * cap with room for the fence and heading: rows are trimmed before the file is.
 */
const SUMMARY_CHARS = 20_000;

/** A changed-line row, cited the way the diff view prints the line: `path` [L<n>]. */
function inDiffRow(h: Hint, contextChars: number, withFlags: boolean): string {
	const flagStr = withFlags
		? Object.entries(h.flags)
				.filter(([, v]) => v !== false && v !== 0 && v !== "")
				.map(([k, v]) => (v === true ? k : `${k}=${v}`))
				.join(", ")
		: "";
	return `- \`${h.file}\` [L${h.line}] — ${h.pattern}${flagStr ? ` [${flagStr}]` : ""}: \`${h.context.slice(0, contextChars)}\``;
}

// A hint about the record rather than a changed line — an ask, a linked issue, a commit — is a row
// of facts the practice decides on, and a flag that is false is one of them (no reply, no change
// near the line), so every flag is shown.
function recordRow(h: Hint): string {
	const flagStr = Object.entries(h.flags)
		.map(([k, v]) => `${k}=${String(v)}`)
		.join(", ");
	return `- \`${h.file}${h.line > 0 ? `:${h.line}` : ""}\` — ${h.pattern}: \`${h.context.slice(0, 160)}\`${flagStr ? ` [${flagStr}]` : ""}`;
}

function renderPractice(result: PracticeResult, recordRows: number, inDiffRows: number): string[] {
	const json = `\`${outputDir}/${result.practice}.json\``;
	const lines = [`## ${result.practice}`];
	if (result.status === "error") {
		lines.push("", `> **Script failed.** Agent must analyze this practice manually.`);
	}
	lines.push("");
	if (result.directions.length > 0) {
		lines.push(...result.directions.map((d) => `- ${d}`), "");
	}

	// A scan of the diff that matched nothing says so with its extent, so the model does not grep
	// again — and says what a scan is, because "nothing matched" alone read as a clean bill: on one
	// holdout the error-handling recall fell by half while the model recorded from this line without
	// enumerating the early returns and discarded results a line pattern cannot see. A script that
	// scanned nothing — a record script, a census — has its directions and no such line.
	const { linesAdded, filesScanned } = result.metrics;
	if (result.hints.length === 0 && result.status === "ok" && linesAdded !== undefined) {
		lines.push(
			`Scanned ${linesAdded} added lines${filesScanned === undefined ? "" : ` in ${filesScanned} files`} for this practice's line patterns; none matched. A pattern sees one line: what spans lines or has no keyword — an early return, a discarded result, a missing else — is yours to enumerate from the diff.`,
			"",
		);
	}

	const inDiffHints = result.hints.filter((h) => h.inDiff);
	if (inDiffHints.length > 0 && inDiffHints.length <= inDiffRows) {
		lines.push("**Key locations (on changed lines):**");
		for (const h of inDiffHints) {
			lines.push(inDiffRow(h, 100, true));
		}
		lines.push("");
	} else if (inDiffHints.length > inDiffRows) {
		const shown = Math.min(IN_DIFF_SAMPLE, inDiffRows);
		lines.push(`**${inDiffHints.length} hints on changed lines** — see ${json} for full list.`);
		for (const h of inDiffHints.slice(0, shown)) {
			lines.push(inDiffRow(h, 80, false));
		}
		lines.push(`- ... and ${inDiffHints.length - shown} more`, "");
	}

	const recordHints = result.hints.filter((h) => !h.inDiff);
	if (recordHints.length > 0) {
		lines.push("**Record facts:**");
		for (const h of recordHints.slice(0, recordRows)) {
			lines.push(recordRow(h));
		}
		if (recordHints.length > recordRows) {
			lines.push(`- ... and ${recordHints.length - recordRows} more in ${json}`);
		}
		lines.push("");
	}
	return lines;
}

const errors = practiceResults.filter((r) => r.status === "error");

function renderSummary(recordRows: number, inDiffRows: number): string {
	const lines: string[] = [
		"# Precomputed Analysis Hints",
		"",
		"> These are **pattern matches and directions to investigate** from static analysis — starting points, not verdicts.",
		"> Use them as starting points — investigate further for things the scripts may have missed.",
		"> Every line of `work/change/diff.patch` carries a `[L<n>] ` prefix before its diff marker, so an added line matches `^\\[L[0-9]+\\] \\+`, never `^\\+`; a row below cites a changed line as `path` [L<n>].",
		"",
	];
	if (errors.length > 0) {
		lines.push(
			`> **${errors.length} script(s) failed** — perform full manual analysis for: ${errors.map((e) => e.practice).join(", ")}`,
			"",
		);
	}
	for (const result of practiceResults) {
		lines.push(...renderPractice(result, recordRows, inDiffRows));
	}
	return lines.join("\n");
}

/** Trim code leads before record facts; retain each practice's pointer to its complete JSON output. */
const MIN_RECORD_ROWS = 3;
const BUDGET_LADDER: [recordRows: number, inDiffRows: number][] = [
	[RECORD_ROWS, IN_DIFF_ROWS],
	[RECORD_ROWS, IN_DIFF_SAMPLE],
	[10, IN_DIFF_SAMPLE],
	[5, IN_DIFF_SAMPLE],
	[5, 0],
	[MIN_RECORD_ROWS, 0],
];
let summary = renderSummary(RECORD_ROWS, IN_DIFF_ROWS);
for (const [recordRows, inDiffRows] of BUDGET_LADDER) {
	if (summary.length <= SUMMARY_CHARS) {
		break;
	}
	summary = renderSummary(recordRows, inDiffRows);
}

await writeFile(`${tmpDir}/summary.md`, summary);

const totalHints = practiceResults.reduce((s, r) => s + r.hints.length, 0);
const inDiffHints = practiceResults.reduce((s, r) => s + r.hints.filter((h) => h.inDiff).length, 0);
const errorCount = errors.length;
await writeFile(
	`${tmpDir}/.timing.json`,
	JSON.stringify({
		durationMs: Date.now() - globalStart,
		practices: practiceResults.length,
		totalHints,
		inDiffHints,
		errors: errorCount,
	}),
);

await writeFile(`${tmpDir}/.complete`, new Date().toISOString());

await rm(outputDir, { recursive: true, force: true });
await rename(tmpDir, outputDir);

console.error(
	JSON.stringify({
		event: "precompute_complete",
		practices: practiceResults.length,
		totalHints,
		inDiffHints,
		errors: errorCount,
		durationMs: Date.now() - globalStart,
	}),
);
