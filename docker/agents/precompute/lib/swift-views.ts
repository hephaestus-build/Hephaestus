/**
 * Where a line of a Swift file lies: the type declaration that encloses it, and whether that type is
 * a SwiftUI view. A precompute script uses this to say "this added line is inside `struct X: View`",
 * which the diff alone cannot show when the hunk starts below the declaration. Brace matching skips
 * string literals and comments; it is a line-placing fact, never a parse of Swift.
 */

import { readFile } from "node:fs/promises";
import { join } from "node:path";

export interface SwiftTypeRange {
	name: string;
	/** `struct`, `class`, `enum`, `actor`, `extension`, `protocol`. */
	kind: string;
	/** Declares conformance to `View` (or `App`/`Scene`, which also have a `body`). */
	isView: boolean;
	/** 1-based, inclusive. */
	start: number;
	end: number;
}

const DECLARATION =
	/^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|fileprivate|internal|final|open)\s+)*(struct|class|enum|actor|extension|protocol)\s+([A-Za-z_][A-Za-z0-9_]*)(?:<[^>{]*>)?\s*(?::\s*([^{]*))?\{?/;

/** Every type declaration of the file with its brace-delimited extent, outermost first. */
export function swiftTypeRanges(source: string): SwiftTypeRange[] {
	const lines = source.split("\n");
	const ranges: SwiftTypeRange[] = [];
	const open: { range: SwiftTypeRange; depth: number }[] = [];
	let depth = 0;
	let inBlockComment = false;
	for (const [index, raw] of lines.entries()) {
		const line = index + 1;
		const declaration = inBlockComment ? null : DECLARATION.exec(raw);
		if (declaration) {
			const [, kind = "", name = "", conformance = ""] = declaration;
			const range: SwiftTypeRange = {
				name,
				kind,
				isView: /\b(?:View|App|Scene)\b/.test(conformance),
				start: line,
				end: lines.length,
			};
			ranges.push(range);
			// The declaration's own `{` is counted below; the type is open from the depth before it.
			open.push({ range, depth });
		}
		let inString = false;
		for (let i = 0; i < raw.length; i++) {
			const ch = raw[i];
			const next = raw[i + 1];
			if (inBlockComment) {
				if (ch === "*" && next === "/") {
					inBlockComment = false;
					i++;
				}
				continue;
			}
			if (inString) {
				if (ch === "\\") i++;
				else if (ch === '"') inString = false;
				continue;
			}
			if (ch === '"') inString = true;
			else if (ch === "/" && next === "/") break;
			else if (ch === "/" && next === "*") {
				inBlockComment = true;
				i++;
			} else if (ch === "{") depth++;
			else if (ch === "}") {
				depth--;
				while (open.length > 0 && depth <= (open.at(-1)?.depth ?? 0)) {
					const closed = open.pop();
					if (closed) closed.range.end = line;
				}
			}
		}
	}
	return ranges;
}

/** The innermost type declaration a line lies in, or undefined at file scope. */
export function enclosingType(ranges: SwiftTypeRange[], line: number): SwiftTypeRange | undefined {
	let best: SwiftTypeRange | undefined;
	for (const range of ranges) {
		if (line < range.start || line > range.end) continue;
		if (!best || range.start >= best.start) best = range;
	}
	return best;
}

/** The type ranges of a file in the checkout, or null when the file is not readable there. */
export async function swiftTypeRangesOf(
	repoPath: string,
	path: string,
): Promise<SwiftTypeRange[] | null> {
	try {
		return swiftTypeRanges(await readFile(join(repoPath, path), "utf8"));
	} catch {
		return null;
	}
}

/** True when the line is a full-line Swift comment. */
export function isSwiftComment(content: string): boolean {
	const trimmed = content.trimStart();
	return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
}
