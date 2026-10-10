/**
 * The positional contract: a script's default export is a function of `(repo, diffFiles, …)` that
 * returns hints, metrics and directions. Injected practice scripts are untyped at runtime. Invalid
 * results throw TypeError; the runner records a per-practice error instead of exposing invalid data
 * to the review.
 */

import { isJsonObject } from "./json.ts";
import { boundSection, SCRIPT_FAILED, SECTION_CHARS } from "./leads.ts";
import type { Hint, HintFlag, PositionalResult, PracticeResult, PracticeScript } from "./types.ts";

// The bundled scripts import these two from here.
export { isJsonObject, text } from "./json.ts";

/**
 * All a dynamic import can prove about a practice module is that it exports a callable default.
 * The signature is unverifiable at run time; the value the call RETURNS is verified by
 * `parsePositionalResult`, which is what actually protects the runner.
 */
export function isPracticeModule(mod: unknown): mod is { default: PracticeScript } {
	return isJsonObject(mod) && typeof mod.default === "function";
}

function isHintFlag(value: unknown): value is HintFlag {
	return typeof value === "boolean" || typeof value === "number" || typeof value === "string";
}

function isString(value: unknown): value is string {
	return typeof value === "string";
}

/**
 * A hint is rendered into its practice's section field by field (`file:line`, `context.slice(...)`,
 * `Object.entries(flags)`), and that rendering happens AFTER the per-practice error boundary — so an
 * incomplete hint that slips through here takes the whole run down instead of one practice.
 */
function isHint(value: unknown): value is Hint {
	return (
		isJsonObject(value) &&
		typeof value.file === "string" &&
		typeof value.line === "number" &&
		typeof value.pattern === "string" &&
		typeof value.context === "string" &&
		typeof value.inDiff === "boolean" &&
		isJsonObject(value.flags) &&
		Object.values(value.flags).every(isHintFlag)
	);
}

/** Validate a positional script's return value. */
export function parsePositionalResult(value: unknown, source: string): PositionalResult {
	if (!isJsonObject(value)) {
		throw new TypeError(`${source} must return an object`);
	}

	const { hints, metrics, directions } = value;
	if (!Array.isArray(hints) || !hints.every(isHint)) {
		throw new TypeError(
			`${source}: hints must be an array of {file, line, pattern, context, inDiff, flags}`,
		);
	}
	if (!Array.isArray(directions) || !directions.every(isString)) {
		throw new TypeError(`${source}: directions must be an array of strings`);
	}
	if (!isJsonObject(metrics)) {
		throw new TypeError(`${source}: metrics must be an object`);
	}

	const numericMetrics: Record<string, number> = {};
	for (const [key, metric] of Object.entries(metrics)) {
		if (typeof metric !== "number" || !Number.isFinite(metric)) {
			// JSON.stringify writes NaN/Infinity as `null`, so an unchecked metric silently becomes a
			// hole in {slug}.json. Naming the key here makes the script's bug findable.
			throw new TypeError(`${source}: metrics.${key} must be a finite number`);
		}
		numericMetrics[key] = metric;
	}

	return { hints, metrics: numericMetrics, directions };
}

/** Record rows shown per practice before the rest is left to the practice's JSON file. */
const RECORD_ROWS = 20;
/** Changed-line rows shown in full per practice; above this, a sample and the JSON pointer. */
const IN_DIFF_ROWS = 10;
const IN_DIFF_SAMPLE = 5;

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

function renderPractice(
	result: PracticeResult,
	jsonPointer: string,
	recordRows: number,
	inDiffRows: number,
): string[] {
	const lines: string[] = [];
	if (result.status !== "ok") {
		lines.push(SCRIPT_FAILED, "");
	}
	if (result.directions.length > 0) {
		lines.push(...result.directions.map((d) => `- ${d}`), "");
	}

	// A scan of the diff that matched nothing says so with its extent, so the model does not grep
	// again. It also says what a line pattern cannot see: "nothing matched" alone reads as a clean
	// result, and the model then does not enumerate the early returns and discarded results itself.
	// A script that scanned nothing — a record script, a census — has its directions and no such line.
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
		lines.push(
			`**${inDiffHints.length} hints on changed lines** — see ${jsonPointer} for full list.`,
		);
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
			lines.push(`- ... and ${recordHints.length - recordRows} more in ${jsonPointer}`);
		}
		lines.push("");
	}
	return lines;
}

/** Changed-line rows drop to a sample first, then record rows: a section keeps some of both, and its pointer. */
const MIN_RECORD_ROWS = 3;
const SECTION_LADDER: [recordRows: number, inDiffRows: number][] = [
	[RECORD_ROWS, IN_DIFF_ROWS],
	[RECORD_ROWS, IN_DIFF_SAMPLE],
	[10, IN_DIFF_SAMPLE],
	[5, IN_DIFF_SAMPLE],
	[MIN_RECORD_ROWS, IN_DIFF_SAMPLE],
];

/** The section a positional result becomes, bounded as every practice's section is. */
export function renderPositional(result: PracticeResult, jsonPointer: string): string {
	let section = "";
	for (const [recordRows, inDiffRows] of SECTION_LADDER) {
		section = renderPractice(result, jsonPointer, recordRows, inDiffRows).join("\n").trim();
		if (section.length <= SECTION_CHARS) {
			break;
		}
	}
	// Directions and rows are not bounded per line, so the smallest step can still overrun.
	return boundSection(section, jsonPointer);
}
