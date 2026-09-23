/** Shared source-line scanning and declaration placement; pattern matches are hints, not verdicts. */

import {
	type Declaration,
	declarationsOf,
	enclosingDeclaration,
	hasDeclarationSyntax,
	isCommentLine,
} from "./declarations.ts";
import { isTestPath, languageOf } from "./languages.ts";
import type { DiffFile, Hint } from "./types.ts";

export type SourcePattern = [label: string, test: RegExp];

export interface Placement {
	/** `struct EventList`, `file scope`, or `unknown` when the checkout or the language row is missing. */
	enclosing: string;
	supertypes: string;
	/** Whether the enclosing declaration's supertypes match the scan's `scope`; null when unknown. */
	inScope: boolean | null;
}

export interface SourceScan {
	hints: Hint[];
	/** Source files of the change in the languages named, tests excluded unless included. */
	filesScanned: number;
	/** Added source lines (comments excluded) in the languages named. */
	linesAdded: number;
	/** Of those, inside a declaration the scope matched. */
	linesInScope: number;
	/** Source files of the change whose declarations could not be read from the checkout. */
	filesWithoutCheckout: number;
}

export interface SourceScanOptions {
	languages: readonly string[];
	patterns: readonly SourcePattern[];
	/** Declarations whose supertypes match are "in scope" — `/\bView\b/` for SwiftUI views. */
	scope?: RegExp;
	/** Report only lines inside a matching declaration (or of unknown placing). */
	onlyInScope?: boolean;
	includeTests?: boolean;
	maxHints?: number;
}

function place(decls: Declaration[] | null, line: number, scope: RegExp | undefined): Placement {
	if (decls === null) {
		return { enclosing: "unknown", supertypes: "", inScope: null };
	}
	const decl = enclosingDeclaration(decls, line);
	if (!decl) {
		return { enclosing: "file scope", supertypes: "", inScope: false };
	}
	return {
		enclosing: `${decl.kind} ${decl.name}`,
		supertypes: decl.supertypes,
		inScope: scope ? scope.test(decl.supertypes) : false,
	};
}

export async function scanAddedLines(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	options: SourceScanOptions,
): Promise<SourceScan> {
	const scan: SourceScan = {
		hints: [],
		filesScanned: 0,
		linesAdded: 0,
		linesInScope: 0,
		filesWithoutCheckout: 0,
	};
	for (const [path, df] of diffFiles) {
		const language = languageOf(path);
		if (language === null || !options.languages.includes(language)) {
			continue;
		}
		if (options.includeTests !== true && isTestPath(path)) {
			continue;
		}
		scan.filesScanned += 1;
		const decls = hasDeclarationSyntax(language)
			? await declarationsOf(repoPath, path, language)
			: null;
		if (decls === null) {
			scan.filesWithoutCheckout += 1;
		}
		for (const [line, content] of df.addedLines) {
			if (isCommentLine(content, language)) {
				continue;
			}
			scan.linesAdded += 1;
			const placement = place(decls, line, options.scope);
			if (placement.inScope === true) {
				scan.linesInScope += 1;
			}
			if (options.onlyInScope === true && placement.inScope === false) {
				continue;
			}
			for (const [label, re] of options.patterns) {
				if (!re.test(content)) {
					continue;
				}
				scan.hints.push({
					file: path,
					line,
					pattern: label,
					context: content.trim().slice(0, 160),
					inDiff: true,
					flags: {
						enclosing: placement.enclosing,
						supertypes: placement.supertypes,
						inScope: placement.inScope ?? "unknown",
					},
				});
				break;
			}
		}
	}
	scan.hints = scan.hints.slice(0, options.maxHints ?? 40);
	return scan;
}

export function countLabel(scan: SourceScan, label: string): number {
	return scan.hints.filter((h) => h.pattern === label).length;
}
