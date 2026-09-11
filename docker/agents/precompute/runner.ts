#!/usr/bin/env node
import { existsSync } from "node:fs";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";

import { globFilesSync } from "./lib/files.ts";

import { parseDiff } from "./lib/diff-parser.ts";
import { isJsonObject, isPracticeModule, parseFindings } from "./lib/practice-contract.ts";
import type { ArtifactMetadata, DiffFile, PracticeResult } from "./lib/types.ts";

const DEFAULT_OUTPUT_DIR = ".precompute";
const DEFAULT_TIMEOUT_MS = 15_000;

const { values } = parseArgs({
	args: process.argv.slice(2),
	options: {
		repo: { type: "string" },
		diff: { type: "string" },
		metadata: { type: "string" },
		context: { type: "string" },
		practices: { type: "string" },
		output: { type: "string", default: DEFAULT_OUTPUT_DIR },
		timeout: { type: "string", default: String(DEFAULT_TIMEOUT_MS) },
	},
});

if (!values.repo) {
	console.error(
		"Usage: node runner.ts --repo <path> --diff <path> [--metadata <path>] [--context <dir>] [--output <dir>]",
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

let diffFiles = new Map<string, DiffFile>();
if (values.diff) {
	try {
		const diffContent = await readFile(values.diff, "utf8");
		diffFiles = parseDiff(diffContent);
		console.error(`Parsed diff: ${diffFiles.size} files`);
	} catch (e) {
		console.error(`Could not parse diff: ${String(e)}`);
	}
}

let metadata: ArtifactMetadata = {};
if (values.metadata) {
	try {
		const parsed: unknown = JSON.parse(await readFile(values.metadata, "utf8"));
		if (isJsonObject(parsed)) {
			metadata = parsed;
		} else {
			console.error(`Metadata ${values.metadata} is not a JSON object; scripts will see {}`);
		}
	} catch (e) {
		console.error(`Could not load metadata: ${String(e)}`);
	}
}

const practicesDir = values.practices ?? `${outputDir}/practices`;
const practiceModules: [string, string][] = [];

if (existsSync(practicesDir)) {
	for (const file of globFilesSync("*.ts", practicesDir)) {
		const slug = file.replace(/\.ts$/, "");
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
	let timer: ReturnType<typeof setTimeout> | undefined;
	try {
		return await Promise.race([
			work,
			new Promise<never>((_, reject) => {
				timer = setTimeout(() => reject(new Error(`Timeout after ${timeoutMs}ms`)), timeoutMs);
			}),
		]);
	} finally {
		clearTimeout(timer);
	}
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
				mod.default(repoPath, diffFiles, metadata, contextDir),
			);
			const result = validateResult(rawResult, slug);
			const elapsed = Date.now() - start;
			console.error(`  ok ${slug}: ${result.hints.length} hints (${elapsed}ms)`);
			return result;
		} catch (e) {
			const elapsed = Date.now() - start;
			const message = messageOf(e);
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

const lines: string[] = [
	"# Precomputed Analysis Hints",
	"",
	"> These are **pattern matches and directions to investigate** from static analysis — starting points, not verdicts.",
	"> Use them as starting points — investigate further for things the scripts may have missed.",
	"",
];

const errors = practiceResults.filter((r) => r.status === "error");
if (errors.length > 0) {
	lines.push(
		`> **${errors.length} script(s) failed** — perform full manual analysis for: ${errors.map((e) => e.practice).join(", ")}`,
		"",
	);
}

for (const result of practiceResults) {
	const inDiffHints = result.hints.filter((h) => h.inDiff);

	lines.push(`## ${result.practice}`);
	if (result.status === "error") {
		lines.push("", `> **Script failed.** Agent must analyze this practice manually.`);
	}
	lines.push("");

	for (const d of result.directions) {
		lines.push(`- ${d}`);
	}
	lines.push("");

	if (inDiffHints.length > 0 && inDiffHints.length <= 10) {
		lines.push("**Key locations (on changed lines):**");
		for (const h of inDiffHints) {
			// Render ALL flag types (boolean, number, string), not just boolean=true
			const flagEntries = Object.entries(h.flags).filter(
				([, v]) => v !== false && v !== 0 && v !== "",
			);
			const flagStr = flagEntries.map(([k, v]) => (v === true ? k : `${k}=${v}`)).join(", ");
			lines.push(
				`- \`${h.file}:${h.line}\` — ${h.pattern}${flagStr ? ` [${flagStr}]` : ""}: \`${h.context.slice(0, 100)}\``,
			);
		}
		lines.push("");
	} else if (inDiffHints.length > 10) {
		lines.push(
			`**${inDiffHints.length} hints on changed lines** — see \`${outputDir}/${result.practice}.json\` for full list.`,
		);
		for (const h of inDiffHints.slice(0, 5)) {
			lines.push(`- \`${h.file}:${h.line}\` — ${h.pattern}: \`${h.context.slice(0, 80)}\``);
		}
		lines.push(`- ... and ${inDiffHints.length - 5} more`, "");
	}
}

await writeFile(`${tmpDir}/summary.md`, lines.join("\n"));

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
