import { noMatchDirections, sampleNote } from "../../lib/core/bounded-text.ts";
import type { PracticeInputs } from "../../lib/core/contract.ts";
import { captureFailure, type ReviewLead } from "../../lib/core/practice-script.ts";
import { blockExtents, enclosingDeclaration, declarations, maskSource } from "./declarations.ts";
import type { Files } from "./index.ts";
import { isTestPath, languageOf } from "./languages.ts";

/** One construct to find: its stable name, the sentence the review reads, and its pattern. */
export interface SiteRule {
	kind: string;
	label: string;
	test: RegExp;
}

export interface EnclosingOptions {
	/** Declarations whose supertypes match are in scope: `/\bView\b/` for SwiftUI views. */
	scope?: RegExp;
	/** Keep only sites in scope, or whose place the language does not let us know. */
	only?: boolean;
}

export interface SiteOptions {
	/** Also match inside comments and strings. The default matches only code. */
	comments?: boolean;
	/** A span that two rules match: keep both rows, or only the first rule's. The default keeps both. */
	overlap?: "keep" | "first";
	/** Add the enclosing type to each site, where the language has declarations. */
	enclosing?: EnclosingOptions;
}

export interface Site {
	path: string;
	line: number;
	endLine: number;
	kind: string;
	label: string;
	language: string | null;
	/** The trimmed first line of the site, at most 160 characters. */
	text: string;
	facts: Readonly<Record<string, string | number | boolean>>;
	lead: ReviewLead;
}

/** A file to read: a changed file with added lines, or the file that a scan assigns. */
export interface SourceFile {
	path: string;
	language: string | null;
	text: string;
	/** Whether the review reads this line: an added line, or any line of a scan's file. */
	isAdded: (line: number) => boolean;
	sites: (rules: readonly SiteRule[], options?: SiteOptions) => readonly Site[];
	/** The brace-delimited extent of each block that a line matching `opener` starts. */
	blocks: (opener: RegExp) => readonly { start: number; end: number }[];
}

export type SiteRules = readonly SiteRule[] | Readonly<Record<string, readonly SiteRule[]>>;

export interface SitesOptions extends SiteOptions {
	/** Also read test files. The default skips them. */
	tests?: boolean;
	/** How many sites become leads. Every count stays complete. */
	listed?: number;
}

export interface FoundSites {
	/** Every selected site, by path and line. */
	sites: readonly Site[];
	/** The first `listed` sites. */
	listed: readonly Site[];
	/** The leads of the listed sites. */
	leads: readonly ReviewLead[];
	matched: number;
	counts: ReadonlyMap<string, number>;
	/** Sites per language of a scanned file; a language with none counts 0. */
	languages: ReadonlyMap<string, number>;
	filesScanned: number;
	linesSelected: number;
	linesInScope: number;
	filesWithUnknownLocation: number;
	/** The direction that says the leads are a sample. Empty when every site is listed. */
	listedNote: readonly string[];
	/** The directions for a scan that matched nothing: what it read, and what it cannot see. */
	noMatch: readonly string[];
}

interface Row {
	rule: SiteRule;
	order: number;
	startLine: number;
	endLine: number;
	start: number;
	end: number;
}

function consumingExtent(source: string, start: number, end: number): number {
	const opener = source[end - 1];
	if (opener !== "(" && opener !== "[") {
		return end;
	}
	const closer = opener === "(" ? ")" : "]";
	let depth = 1;
	for (let at = end; at < source.length; at += 1) {
		if (source[at] === opener) {
			depth += 1;
		} else if (source[at] === closer) {
			depth -= 1;
			if (depth === 0) {
				return at + 1;
			}
		}
	}
	return Math.max(start, end);
}

function matches(
	language: string | null,
	text: string,
	rules: readonly SiteRule[],
	comments: boolean,
): Row[] {
	const executable = comments ? undefined : maskSource(language, text);
	const lineAt = (offset: number) => text.slice(0, offset).split("\n").length;
	const found: Row[] = [];
	for (const [order, rule] of rules.entries()) {
		const flags = rule.test.flags.includes("g") ? rule.test.flags : `${rule.test.flags}g`;
		for (const match of text.matchAll(new RegExp(rule.test.source, flags))) {
			const first = match[0].search(/\S/u);
			if (
				executable !== undefined &&
				first >= 0 &&
				executable.slice(match.index + first, match.index + first + 1).trim() === ""
			) {
				continue;
			}
			const end = match.index + match[0].trimEnd().length;
			found.push({
				rule,
				order,
				start: match.index + Math.max(0, first),
				end,
				startLine: lineAt(match.index + Math.max(0, first)),
				endLine: lineAt(consumingExtent(executable ?? text, match.index, end) - 1),
			});
		}
	}
	return found.toSorted((a, b) => a.startLine - b.startLine || a.endLine - b.endLine);
}

/** Of rows that overlap on one line, the first rule's. */
function firstRuleOnly(rows: readonly Row[]): Row[] {
	const kept: Row[] = [];
	for (const row of rows.toSorted((a, b) => a.order - b.order)) {
		const overlaps = kept.some(
			(other) =>
				other.startLine === row.startLine && row.start < other.end && other.start < row.end,
		);
		if (!overlaps) {
			kept.push(row);
		}
	}
	return kept.toSorted((a, b) => a.startLine - b.startLine || a.start - b.start);
}

function place(
	language: string | null,
	text: string,
	options: EnclosingOptions,
): (row: Row) => Record<string, string | boolean> | null {
	const decls = language === null ? null : declarations(language, text);
	const { scope } = options;
	const expression =
		scope === undefined
			? undefined
			: new RegExp(scope.source, scope.flags.replaceAll(/[gy]/gu, ""));
	return (row) => {
		const decl = decls === null ? undefined : enclosingDeclaration(decls, row.startLine);
		let inScope: boolean | "unknown" = "unknown";
		if (decls !== null) {
			inScope = decl !== undefined && expression?.test(decl.supertypes) === true;
		}
		if (options.only === true && inScope === false) {
			return null;
		}
		let enclosing = "unknown";
		if (decls !== null) {
			enclosing = decl === undefined ? "file scope" : `${decl.kind} ${decl.name}`;
		}
		return { enclosing, supertypes: decl?.supertypes ?? "", inScope };
	};
}

export function sourceFile(
	path: string,
	text: string,
	isAdded: (line: number) => boolean,
): SourceFile {
	const language = languageOf(path);
	const lines = text.split("\n");
	const sites = (rules: readonly SiteRule[], options: SiteOptions = {}): readonly Site[] => {
		const all = matches(language, text, rules, options.comments === true);
		const rows = options.overlap === "first" ? firstRuleOnly(all) : all;
		const placed =
			options.enclosing === undefined ? null : place(language, text, options.enclosing);
		return rows.flatMap((row) => {
			let selected = false;
			for (let line = row.startLine; line <= row.endLine && !selected; line += 1) {
				selected = isAdded(line);
			}
			const where = selected ? placed?.(row) : undefined;
			if (!selected || where === null) {
				return [];
			}
			const facts = { language: language ?? "unknown", ...where };
			return [
				{
					path,
					line: row.startLine,
					endLine: row.endLine,
					kind: row.rule.kind,
					label: row.rule.label,
					language,
					text: lines[row.startLine - 1]?.trim().slice(0, 160) ?? "",
					facts,
					lead: {
						at: { file: path, line: row.startLine, endLine: row.endLine },
						label: row.rule.label,
						facts,
					},
				},
			];
		});
	};
	return {
		path,
		language,
		text,
		isAdded,
		sites,
		blocks: (opener) => (language === null ? [] : (blockExtents(language, text, opener) ?? [])),
	};
}

export async function selectedSources(
	inputs: Pick<PracticeInputs, "scope">,
	files: Files | null,
	select: (file: { path: string; language: string | null }) => boolean,
): Promise<readonly SourceFile[]> {
	if (files === null) {
		throw captureFailure("unavailable", "the pinned file tree was not captured");
	}
	const scan = inputs.scope.kind === "SCAN";
	if (!scan && files.change === null) {
		throw captureFailure("unavailable", "the change was not captured");
	}
	const paths = scan ? [inputs.scope.item.path] : [...(files.change?.files.keys() ?? [])];
	const sources: SourceFile[] = [];
	for (const path of paths) {
		if (path === undefined) {
			throw captureFailure("unavailable", "the assigned file has no path");
		}
		const file = { path, language: languageOf(path) };
		const added = files.change?.files.get(path)?.addedLines;
		if (!scan && ((added?.size ?? 0) === 0 || !select(file))) {
			continue;
		}
		const read = await files.head.read(path);
		if (read.status !== "FOUND") {
			throw captureFailure("unavailable", `${path} is not readable text`);
		}
		if (read.truncated) {
			throw captureFailure("too-large", `${path} was not read completely`);
		}
		if (select(file)) {
			sources.push(
				sourceFile(path, read.text, scan ? () => true : (line) => added?.has(line) === true),
			);
		}
	}
	return sources;
}

const DEFAULT_LISTED = 40;

const isList = (rules: SiteRules): rules is readonly SiteRule[] => Array.isArray(rules);

function rulesFor(rules: SiteRules, language: string | null): readonly SiteRule[] | undefined {
	if (isList(rules)) {
		return rules;
	}
	return language === null ? undefined : rules[language];
}

/** Search the selected source files in full, and keep the sites on the lines that the review reads. */
export async function findSites(
	inputs: Pick<PracticeInputs, "scope">,
	files: Files | null,
	rules: SiteRules,
	options: SitesOptions = {},
): Promise<FoundSites> {
	const sources = await selectedSources(
		inputs,
		files,
		(file) =>
			rulesFor(rules, file.language) !== undefined &&
			(options.tests === true || !isTestPath(file.path)),
	);
	const sites: Site[] = [];
	const counts = new Map<string, number>();
	const languages = new Map<string, number>();
	let linesSelected = 0;
	let linesInScope = 0;
	let filesWithUnknownLocation = 0;
	for (const file of sources) {
		linesSelected +=
			inputs.scope.kind === "SCAN"
				? file.text.split("\n").length
				: (files?.change?.files.get(file.path)?.addedLines.size ?? 0);
		const found = file.sites(rulesFor(rules, file.language) ?? [], options);
		if (found.some((site) => site.facts.enclosing === "unknown")) {
			filesWithUnknownLocation += 1;
		}
		for (const site of found) {
			counts.set(site.kind, (counts.get(site.kind) ?? 0) + 1);
			linesInScope += site.facts.inScope === true ? 1 : 0;
		}
		const language = file.language ?? "unknown";
		languages.set(language, (languages.get(language) ?? 0) + found.length);
		sites.push(...found);
	}
	const byPlace = (a: Site, b: Site) => {
		if (a.path !== b.path) {
			return a.path < b.path ? -1 : 1;
		}
		return a.line - b.line;
	};
	const ordered = sites.toSorted(byPlace);
	const listed = ordered.slice(0, options.listed ?? DEFAULT_LISTED);
	const leads = listed.map((site) => site.lead);
	return {
		sites: ordered,
		listed,
		leads,
		matched: ordered.length,
		counts,
		languages,
		filesScanned: sources.length,
		linesSelected,
		linesInScope,
		filesWithUnknownLocation,
		listedNote: sampleNote({ matched: ordered.length, hints: leads }),
		noMatch: noMatchDirections(linesSelected, sources.length),
	};
}
