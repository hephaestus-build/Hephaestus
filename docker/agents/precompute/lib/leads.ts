/**
 * The runner's side of a definition script's result. The runner writes each quote from the source, so
 * a lead cannot quote words that the source does not hold.
 */
import { realpathSync, statSync, readFileSync } from "node:fs";
import path from "node:path";

import {
	isFailed,
	type Citation,
	type Lead,
	type ModelSlot,
	type Need,
	type Precomputed,
	type Reason,
	type RunStatus,
	type Unrated,
} from "./contract.ts";
import type { DiffFile } from "./types.ts";

/** A file larger than this is not quoted from. */
const MAX_QUOTED_FILE_BYTES = 2 * 1024 * 1024;
/** Lines one quote may span. */
const MAX_QUOTE_LINES = 20;
/**
 * A practice's section is inlined beside its criteria in the turn that evaluates it, so each section is
 * bounded on its own: one busy script cannot crowd another practice's leads out of the prompt.
 */
export const SECTION_CHARS = 3000;

/** The first line of the section of a script that failed, whichever its contract. */
export const SCRIPT_FAILED = "> **Script failed.** Agent must analyze this practice manually.";

/** A section cut to its bound at a line break, or mid-line when one line alone overruns, with a pointer to the full result. */
export function boundSection(section: string, jsonPointer: string): string {
	if (section.length <= SECTION_CHARS) {
		return `${section}\n`;
	}
	const pointer = `- ... the rest is in ${jsonPointer}`;
	const kept = section.slice(0, SECTION_CHARS - pointer.length - 2);
	const lineEnd = kept.lastIndexOf("\n");
	return `${lineEnd > 0 ? kept.slice(0, lineEnd) : kept}\n${pointer}\n`;
}

export interface Sources {
	change: Map<string, DiffFile>;
	repo: string;
	contextDir: string;
	contextReference: string;
}

interface QuotedLead extends Lead {
	quote: string;
}

/** A path inside `root`, symlinks resolved, naming a regular file; otherwise undefined. */
function fileInside(root: string, relative: string): string | undefined {
	try {
		const base = realpathSync(root);
		const full = realpathSync(path.resolve(base, relative));
		if (full !== base && !full.startsWith(`${base}${path.sep}`)) {
			return undefined;
		}
		const stats = statSync(full);
		return stats.isFile() && stats.size <= MAX_QUOTED_FILE_BYTES ? full : undefined;
	} catch {
		return undefined;
	}
}

function linesOf(
	lines: ReadonlyMap<number, string>,
	start: number,
	end: number,
): string | undefined {
	if (end < start || end - start >= MAX_QUOTE_LINES) {
		return undefined;
	}
	const picked: string[] = [];
	for (let n = start; n <= end; n += 1) {
		const text = lines.get(n);
		if (text === undefined) {
			return undefined;
		}
		picked.push(text);
	}
	return picked.join("\n");
}

function numbered(file: string): Map<number, string> {
	return new Map(
		readFileSync(file, "utf8")
			.split("\n")
			.map((text, i) => [i + 1, text]),
	);
}

/** The text a citation names, or undefined when the source does not hold it. */
function quoteOf(citation: Citation, sources: Sources): string | undefined {
	if ("change" in citation) {
		const file = sources.change.get(citation.change);
		if (file === undefined) {
			return undefined;
		}
		const side = citation.side === "OLD" ? file.removedLines : file.addedLines;
		return linesOf(side, citation.line, citation.endLine ?? citation.line);
	}
	if ("file" in citation) {
		const file = fileInside(sources.repo, citation.file);
		return file === undefined
			? undefined
			: linesOf(numbered(file), citation.line, citation.endLine ?? citation.line);
	}
	if (sources.contextDir === "") {
		return undefined;
	}
	const file = fileInside(sources.contextDir, citation.record);
	if (file === undefined) {
		return undefined;
	}
	// A record cited as a whole (line 0 or none) is quoted by name only.
	return citation.line === undefined || citation.line === 0
		? ""
		: linesOf(numbered(file), citation.line, citation.line);
}

/**
 * Keep the leads whose kind the script declared and whose citation the source holds, each with the
 * quote the runner read. The rest are counted.
 */
export function settleLeads(
	precomputed: Precomputed,
	kinds: Record<string, string>,
	sources: Sources,
): { leads: QuotedLead[]; dropped: number } {
	const leads: QuotedLead[] = [];
	let dropped = 0;
	for (const lead of precomputed.leads) {
		const quote = Object.hasOwn(kinds, lead.kind) ? quoteOf(lead.at, sources) : undefined;
		if (quote === undefined) {
			dropped += 1;
		} else {
			leads.push({ ...lead, quote });
		}
	}
	return { leads, dropped };
}

/**
 * One slot of a definition script: what it needs, whether a model was bound, and its calls that the
 * runner could not rate, by reason. The proxy counts calls and tokens.
 */
export interface SlotReport {
	need: Need;
	bound: boolean;
	notRated: Partial<Record<Reason, number>>;
}

/** What a definition script did, as `<slug>.json` records it. */
export interface DefinitionResult {
	practice: string;
	contract: "definition";
	status: RunStatus;
	models: Partial<Record<ModelSlot, SlotReport>>;
	leads: QuotedLead[];
	facts: Precomputed["facts"];
	unrated: Unrated[];
	directions: string[];
	kinds: Record<string, string>;
	dropped: number;
	/** Why the script failed or was stopped. */
	error?: string;
}

function where(citation: Citation, contextReference: string): string {
	if ("change" in citation) {
		const range =
			citation.endLine === undefined ? `${citation.line}` : `${citation.line}-${citation.endLine}`;
		return `\`${citation.change}\` [L${range}]${citation.side === "OLD" ? " (removed)" : ""}`;
	}
	if ("file" in citation) {
		const range =
			citation.endLine === undefined ? `${citation.line}` : `${citation.line}-${citation.endLine}`;
		return `\`${citation.file}:${range}\``;
	}
	const record = path.posix.join(contextReference, citation.record);
	return `\`${record}${citation.line === undefined || citation.line === 0 ? "" : `:${citation.line}`}\``;
}

function leadRow(lead: QuotedLead, contextReference: string): string {
	const facts = Object.entries(lead.facts ?? {})
		.map(([key, value]) => `${key}=${String(value)}`)
		.join(", ");
	const quote = lead.quote.split("\n")[0]?.trim().slice(0, 120) ?? "";
	return `- ${lead.kind}${lead.rating === undefined ? "" : " (model-rated)"} at ${where(lead.at, contextReference)}${quote ? `: \`${quote}\`` : ""}${facts ? ` [${facts}]` : ""}`;
}

/** The section a definition script's result becomes, bounded as every practice's section is. */
export function renderDefinition(
	result: DefinitionResult,
	contextReference: string,
	jsonPointer: string,
): string {
	const lines: string[] = [];
	if (isFailed(result.status)) {
		lines.push(SCRIPT_FAILED, "");
	}
	if (result.status === "skipped") {
		const needed = Object.entries(result.models)
			.filter(([, slot]) => slot.need === "required" && !slot.bound)
			.map(([name]) => name);
		lines.push(
			`> **Not run.** No model is available for a slot that this practice's precompute requires: ${needed.join(", ")}. Assess it from the sources yourself.`,
			"",
		);
	}
	lines.push(...result.directions.map((direction) => `- ${direction}`));
	const used = new Set(result.leads.map((lead) => lead.kind));
	if (used.size > 0) {
		lines.push(
			"",
			"**Lead kinds:**",
			...[...used].map((kind) => `- ${kind}: ${result.kinds[kind] ?? ""}`),
			"",
			"**Leads:**",
			...result.leads.map((lead) => leadRow(lead, contextReference)),
		);
	}
	const facts = Object.entries(result.facts ?? {});
	if (facts.length > 0) {
		lines.push("", "**Facts:**", ...facts.map(([key, value]) => `- ${key} = ${String(value)}`));
	}
	const unrated = [...result.unrated];
	if (result.dropped > 0) {
		unrated.push({
			what: "lead(s) with a kind that the script did not declare or cited lines that the source does not hold",
			count: result.dropped,
			reason: "off-format",
		});
	}
	for (const item of unrated) {
		lines.push(`- Not rated: ${item.count} ${item.what} (${item.reason}). Read those yourself.`);
	}
	return boundSection(lines.join("\n").trim(), jsonPointer);
}
