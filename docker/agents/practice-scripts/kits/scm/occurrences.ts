import type { PracticeInputs, ReviewLead } from "../../lib/core/contract.ts";
import { captureFailure } from "../../lib/core/practice-script.ts";
import {
	type Declaration,
	declarations,
	enclosingDeclaration,
	maskSource,
} from "./declarations.ts";
import type { Files } from "./index.ts";
import { languageOf } from "./languages.ts";
import type { DiffFile } from "./types.ts";

export interface SourceFile {
	path: string;
	language: string | null;
	text: string;
}
export interface Occurrence {
	path: string;
	startLine: number;
	endLine: number;
	label: string;
	facts: Readonly<Record<string, string | number | boolean>>;
	match?: { start: number; end: number; pattern: number };
}
export interface OccurrencePattern {
	label: string;
	test: RegExp;
}

export function distinctOccurrences(found: readonly Occurrence[]): readonly Occurrence[] {
	const kept: Occurrence[] = [];
	const byLine = new Map<string, Occurrence[]>();
	const ranked = found.toSorted((a, b) => (a.match?.pattern ?? 0) - (b.match?.pattern ?? 0));
	for (const row of ranked) {
		const at = row.match;
		if (at === undefined) {
			kept.push(row);
			continue;
		}
		const key = `${row.path}\0${row.startLine}`;
		const peers = byLine.get(key) ?? [];
		if (
			peers.some((other) => {
				const previous = other.match;
				return (
					previous !== undefined &&
					other.path === row.path &&
					at.start < previous.end &&
					previous.start < at.end
				);
			})
		) {
			continue;
		}
		kept.push(row);
		peers.push(row);
		byLine.set(key, peers);
	}
	return kept.toSorted(
		(a, b) =>
			a.path.localeCompare(b.path) ||
			a.startLine - b.startLine ||
			(a.match?.start ?? 0) - (b.match?.start ?? 0),
	);
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

export function occurrences(
	file: SourceFile,
	patterns: readonly OccurrencePattern[],
	options: { includeComments?: boolean } = {},
): readonly Occurrence[] {
	const { text } = file;
	const executable =
		options.includeComments === true ? undefined : maskSource(file.language, file.text);
	const found: Occurrence[] = [];
	const lineAt = (offset: number) => text.slice(0, offset).split("\n").length;
	for (const [pattern, { label, test }] of patterns.entries()) {
		const expression = new RegExp(
			test.source,
			test.flags.includes("g") ? test.flags : `${test.flags}g`,
		);
		for (const match of text.matchAll(expression)) {
			const first = match[0].search(/\S/u);
			if (
				executable !== undefined &&
				first >= 0 &&
				executable.slice(match.index + first, match.index + first + 1).trim() === ""
			) {
				continue;
			}
			const startLine = lineAt(match.index + Math.max(0, first));
			const row: Occurrence = {
				path: file.path,
				startLine,
				endLine: lineAt(
					consumingExtent(
						executable ?? file.text,
						match.index,
						match.index + match[0].trimEnd().length,
					) - 1,
				),
				label,
				facts: { language: file.language ?? "unknown" },
				match: {
					start: match.index + Math.max(0, first),
					end: match.index + match[0].trimEnd().length,
					pattern,
				},
			};
			found.push(row);
		}
	}
	return found.toSorted((a, b) => a.startLine - b.startLine || a.endLine - b.endLine);
}
export function selectAddedOccurrences(
	found: readonly Occurrence[],
	change: ReadonlyMap<string, DiffFile>,
): readonly Occurrence[] {
	return found.filter((row) => {
		const lines = change.get(row.path)?.addedLines;
		for (let line = row.startLine; line <= row.endLine; line += 1) {
			if (lines?.has(line) === true) {
				return true;
			}
		}
		return false;
	});
}
export function placeOccurrences(
	file: SourceFile,
	found: readonly Occurrence[],
	options: { scope?: RegExp; onlyInScope?: boolean } = {},
): readonly Occurrence[] {
	const decls = file.language === null ? null : declarations(file.language, file.text);
	return found.flatMap((row) => {
		const decl = decls === null ? undefined : enclosingDeclaration(decls, row.startLine);
		const expression =
			options.scope === undefined
				? undefined
				: new RegExp(options.scope.source, options.scope.flags.replaceAll(/[gy]/gu, ""));
		let inScope: boolean | "unknown" = "unknown";
		if (decls !== null) {
			inScope = decl !== undefined && expression?.test(decl.supertypes) === true;
		}
		if (options.onlyInScope === true && inScope === false) {
			return [];
		}
		return [
			{
				...row,
				facts: {
					...row.facts,
					enclosing: declarationName(decls, decl),
					supertypes: decl?.supertypes ?? "",
					inScope,
				},
			},
		];
	});
}
export async function selectedSources(
	inputs: PracticeInputs,
	files: Files | null,
	select: (file: Pick<SourceFile, "path" | "language">) => boolean,
): Promise<readonly SourceFile[]> {
	if (files === null) {
		throw captureFailure("unavailable", "the pinned file tree was not captured");
	}
	const paths =
		inputs.scope.kind === "SCAN"
			? [inputs.scope.item.path]
			: [...(files.change?.files.keys() ?? [])];
	if (inputs.scope.kind === "ITEM" && files.change === null) {
		throw captureFailure("unavailable", "the change was not captured");
	}
	const sources: SourceFile[] = [];
	for (const path of paths) {
		if (path === undefined) {
			throw captureFailure("unavailable", "the assigned file has no path");
		}
		const file = { path, language: languageOf(path) };
		if (
			inputs.scope.kind === "ITEM" &&
			((files.change?.files.get(path)?.addedLines.size ?? 0) === 0 || !select(file))
		) {
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
			sources.push({ ...file, text: read.text });
		}
	}
	return sources;
}
export function selectedOccurrences(
	inputs: PracticeInputs,
	files: Files | null,
	found: readonly Occurrence[],
): readonly Occurrence[] {
	if (inputs.scope.kind === "SCAN") {
		return found;
	}
	if (files?.change == null) {
		throw captureFailure("unavailable", "the change was not captured");
	}
	return selectAddedOccurrences(found, files.change.files);
}
export function occurrenceLead(row: Occurrence): ReviewLead {
	return {
		at: { file: row.path, line: row.startLine, endLine: row.endLine },
		label: row.label,
		facts: { ...row.facts },
	};
}

function declarationName(decls: Declaration[] | null, decl: Declaration | undefined): string {
	if (decls === null) {
		return "unknown";
	}
	if (decl === undefined) {
		return "file scope";
	}
	return `${decl.kind} ${decl.name}`;
}

function leadPath(lead: ReviewLead): string {
	if ("file" in lead.at) {
		return lead.at.file;
	}
	if ("change" in lead.at) {
		return lead.at.change;
	}
	return lead.at.record;
}
export function sortOccurrenceLeads(leads: readonly ReviewLead[]): ReviewLead[] {
	return leads.toSorted((a, b) => {
		const left = leadPath(a);
		const right = leadPath(b);
		if (left < right) {
			return -1;
		}
		if (left > right) {
			return 1;
		}
		return (a.at.line ?? 0) - (b.at.line ?? 0);
	});
}
