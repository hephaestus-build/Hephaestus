/**
 * Heuristic declaration spans for brace-delimited languages, not a syntax parser. Strings and
 * comments are skipped. Unsupported languages return null so callers report location as unknown.
 */

export interface Declaration {
	/** `struct`, `class`, `enum`, `actor`, `extension`, `protocol`, `interface`, `object`, `record`, `impl`, `trait`, `mod`, `type`. */
	kind: string;
	name: string;
	/** The text between the name and the opening brace: the supertypes, conformances or interfaces. */
	supertypes: string;
	/** 1-based, inclusive. */
	start: number;
	end: number;
}

interface StringSyntax {
	open: string;
	close: string;
	/** Escape character inside the literal; none for raw literals. */
	escape?: string;
	/** An interpolation opener whose balanced body may itself hold strings and braces: `\(` or `${`. */
	interpolation?: [open: string, openBracket: string, closeBracket: string];
	/** Swift `#"…"#`: the literal may be prefixed by hashes that the closing delimiter must repeat. */
	hashPrefixed?: boolean;
}

interface Syntax {
	/** Named groups `kind`, `name`, `supertypes`; tested against each line. */
	declaration: RegExp;
	lineComment: string;
	blockComment?: [string, string];
	nestedBlockComments?: boolean;
	strings: StringSyntax[];
}

const MODIFIERS = String.raw`(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|fileprivate|internal|protected|open|final|abstract|static|sealed|data|inner|value|indirect|partial|export|default|declare|pub(?:\([^)]*\))?|unsafe)\s+)*`;

const TYPESCRIPT: Syntax = {
	declaration: new RegExp(
		String.raw`^\s*${MODIFIERS}(?<kind>class|interface|enum|namespace)\s+(?<name>[A-Za-z_$][\w$]*)(?:<[^{]*?>)?\s*(?:(?:extends|implements)\s+(?<supertypes>[^{]*))?`,
		"u",
	),
	lineComment: "//",
	blockComment: ["/*", "*/"],
	strings: [
		{ open: "`", close: "`", escape: "\\", interpolation: ["${", "{", "}"] },
		{ open: '"', close: '"', escape: "\\" },
		{ open: "'", close: "'", escape: "\\" },
	],
};

const SYNTAX: Record<string, Syntax> = {
	swift: {
		declaration: new RegExp(
			String.raw`^\s*${MODIFIERS}(?<kind>struct|class|enum|actor|extension|protocol)\s+(?<name>[A-Za-z_][\w.]*)(?:<[^{]*?>)?\s*(?::\s*(?<supertypes>[^{]*))?`,
			"u",
		),
		lineComment: "//",
		blockComment: ["/*", "*/"],
		nestedBlockComments: true,
		strings: [
			{
				open: '"""',
				close: '"""',
				escape: "\\",
				interpolation: [String.raw`\(`, "(", ")"],
				hashPrefixed: true,
			},
			{
				open: '"',
				close: '"',
				escape: "\\",
				interpolation: [String.raw`\(`, "(", ")"],
				hashPrefixed: true,
			},
		],
	},
	kotlin: {
		declaration: new RegExp(
			String.raw`^\s*${MODIFIERS}(?:(?:enum|annotation|sealed|data|inner|value)\s+)*(?<kind>class|object|interface)\s+(?<name>[A-Za-z_]\w*)(?:<[^{]*?>)?\s*(?:\([^)]*\)\s*)?(?::\s*(?<supertypes>[^{]*))?`,
			"u",
		),
		lineComment: "//",
		blockComment: ["/*", "*/"],
		nestedBlockComments: true,
		strings: [
			{ open: '"""', close: '"""', interpolation: ["${", "{", "}"] },
			{ open: '"', close: '"', escape: "\\", interpolation: ["${", "{", "}"] },
			{ open: "'", close: "'", escape: "\\" },
		],
	},
	java: {
		declaration: new RegExp(
			String.raw`^\s*${MODIFIERS}(?<kind>class|interface|enum|record)\s+(?<name>[A-Za-z_]\w*)(?:<[^{]*?>)?\s*(?:\([^)]*\)\s*)?(?:(?:extends|implements|permits)\s+(?<supertypes>[^{]*))?`,
			"u",
		),
		lineComment: "//",
		blockComment: ["/*", "*/"],
		strings: [
			{ open: '"""', close: '"""', escape: "\\" },
			{ open: '"', close: '"', escape: "\\" },
			{ open: "'", close: "'", escape: "\\" },
		],
	},
	typescript: TYPESCRIPT,
	javascript: TYPESCRIPT,
	csharp: {
		declaration: new RegExp(
			String.raw`^\s*${MODIFIERS}(?:(?:readonly|ref|record)\s+)*(?<kind>class|struct|interface|enum|record)\s+(?<name>[A-Za-z_]\w*)(?:<[^{]*?>)?\s*(?:\([^)]*\)\s*)?(?::\s*(?<supertypes>[^{]*))?`,
			"u",
		),
		lineComment: "//",
		blockComment: ["/*", "*/"],
		strings: [
			{ open: '$"', close: '"', escape: "\\", interpolation: ["{", "{", "}"] },
			{ open: '@"', close: '"' },
			{ open: '"', close: '"', escape: "\\" },
			{ open: "'", close: "'", escape: "\\" },
		],
	},
	go: {
		declaration:
			/^\s*type\s+(?<name>[A-Za-z_]\w*)(?:\[[^\]]*\])?\s+(?<kind>struct|interface)\s*(?<supertypes>)/u,
		lineComment: "//",
		blockComment: ["/*", "*/"],
		strings: [
			{ open: "`", close: "`" },
			{ open: '"', close: '"', escape: "\\" },
			{ open: "'", close: "'", escape: "\\" },
		],
	},
	rust: {
		declaration: new RegExp(
			String.raw`^\s*${MODIFIERS}(?<kind>struct|enum|trait|impl|mod|union)\s*(?:<[^{]*?>\s*)?(?<name>[A-Za-z_][\w:<>]*)(?:\s+for\s+(?<supertypes>[^{]*))?`,
			"u",
		),
		lineComment: "//",
		blockComment: ["/*", "*/"],
		nestedBlockComments: true,
		strings: [
			{ open: 'r#"', close: '"#' },
			{ open: '"', close: '"', escape: "\\" },
		],
	},
};
function startsWith(source: string, i: number, token: string): boolean {
	return source.startsWith(token, i);
}

function opensAt(source: string, i: number, spec: StringSyntax): number | null {
	let hashes = 0;
	if (spec.hashPrefixed === true) {
		while (source[i + hashes] === "#") {
			hashes += 1;
		}
	}
	if (hashes > 0 && spec.hashPrefixed !== true) {
		return null;
	}
	return startsWith(source, i + hashes, spec.open) ? hashes : null;
}

function stringOpeningAt(source: string, i: number, all: StringSyntax[]): StringSyntax | undefined {
	for (const spec of all) {
		if (opensAt(source, i, spec) !== null) {
			return spec;
		}
	}
	return undefined;
}

// Raw Swift strings place delimiter hashes between the interpolation backslash and parenthesis.
function interpolationOpener(spec: StringSyntax, hashes: number, raw: string): string | undefined {
	if (spec.interpolation === undefined) {
		return undefined;
	}
	if (hashes > 0 && spec.escape !== undefined) {
		return spec.escape + raw + spec.interpolation[0].slice(spec.escape.length);
	}
	return spec.interpolation[0];
}

function skipString(source: string, from: number, spec: StringSyntax, all: StringSyntax[]): number {
	const hashes = opensAt(source, from, spec) ?? 0;
	const raw = "#".repeat(hashes);
	let i = from + hashes + spec.open.length;
	const close = spec.close + raw;
	const escape = spec.escape === undefined ? undefined : spec.escape + raw;
	const interpolation = interpolationOpener(spec, hashes, raw);
	while (i < source.length) {
		if (startsWith(source, i, close)) {
			return i + close.length;
		}
		if (
			interpolation !== undefined &&
			spec.interpolation !== undefined &&
			startsWith(source, i, interpolation)
		) {
			const [, openBracket, closeBracket] = spec.interpolation;
			i = skipBalanced(source, i + interpolation.length, openBracket, closeBracket, all);
			continue;
		}
		if (escape !== undefined && startsWith(source, i, escape)) {
			i += escape.length + 1;
			continue;
		}
		i += 1;
	}
	return i;
}

function skipBalanced(
	source: string,
	from: number,
	openBracket: string,
	closeBracket: string,
	all: StringSyntax[],
): number {
	let depth = 1;
	let i = from;
	while (i < source.length && depth > 0) {
		const nested = stringOpeningAt(source, i, all);
		if (nested) {
			i = skipString(source, i, nested, all);
			continue;
		}
		if (startsWith(source, i, openBracket)) {
			depth += 1;
		} else if (startsWith(source, i, closeBracket)) {
			depth -= 1;
		}
		i += 1;
	}
	return i;
}

function braceEvents(source: string, syntax: Syntax): { brace: "{" | "}"; line: number }[] {
	const events: { brace: "{" | "}"; line: number }[] = [];
	let line = 1;
	let i = 0;
	const advanceTo = (j: number) => {
		for (let k = i; k < j && k < source.length; k += 1) {
			if (source[k] === "\n") {
				line += 1;
			}
		}
		i = j;
	};
	while (i < source.length) {
		const ch = source[i];
		if (ch === "\n") {
			line += 1;
			i += 1;
			continue;
		}
		if (startsWith(source, i, syntax.lineComment)) {
			const end = source.indexOf("\n", i);
			advanceTo(end === -1 ? source.length : end);
			continue;
		}
		if (syntax.blockComment && startsWith(source, i, syntax.blockComment[0])) {
			const [open, close] = syntax.blockComment;
			let depth = 1;
			let j = i + open.length;
			while (j < source.length && depth > 0) {
				if (syntax.nestedBlockComments === true && startsWith(source, j, open)) {
					depth += 1;
					j += open.length;
				} else if (startsWith(source, j, close)) {
					depth -= 1;
					j += close.length;
				} else {
					j += 1;
				}
			}
			advanceTo(j);
			continue;
		}
		const string = stringOpeningAt(source, i, syntax.strings);
		if (string) {
			advanceTo(skipString(source, i, string, syntax.strings));
			continue;
		}
		if (ch === "{" || ch === "}") {
			events.push({ brace: ch, line });
		}
		i += 1;
	}
	return events;
}

/** Every type declaration of the file with its brace-delimited extent, in source order. */
export function declarations(language: string, source: string): Declaration[] | null {
	const syntax = SYNTAX[language];
	if (!syntax) {
		return null;
	}
	const lines = source.split("\n");
	const events = braceEvents(source, syntax);
	// Each declaration claims the first unclaimed `{` at or after its line. A header may run on over
	// several lines, but never across a blank line or another declaration: a Kotlin data class or a
	// Java record with no body, a protocol requirement, has no extent and is dropped.
	const claimed = new Map<number, Declaration>();
	let next = 0;
	for (const [index, text] of lines.entries()) {
		const match = syntax.declaration.exec(text);
		if (!match?.groups) {
			continue;
		}
		const { kind = "", name = "", supertypes = "" } = match.groups;
		while (
			next < events.length &&
			!(events[next]?.brace === "{" && (events[next]?.line ?? 0) >= index + 1)
		) {
			next += 1;
		}
		if (next === events.length) {
			break;
		}
		const braceLine = events[next]?.line ?? 0;
		const header = lines.slice(index + 1, braceLine - 1);
		if (header.some((l) => l.trim() === "" || syntax.declaration.test(l))) {
			continue;
		}
		claimed.set(next, {
			kind,
			name,
			supertypes: supertypes.trim(),
			start: index + 1,
			end: lines.length,
		});
		next += 1;
	}
	const found: Declaration[] = [];
	const open: { decl: Declaration; depth: number }[] = [];
	let depth = 0;
	for (const [k, event] of events.entries()) {
		if (event.brace === "{") {
			const decl = claimed.get(k);
			if (decl) {
				found.push(decl);
				open.push({ decl, depth });
			}
			depth += 1;
		} else {
			depth -= 1;
			while (open.length > 0 && depth <= (open.at(-1)?.depth ?? 0)) {
				const closed = open.pop();
				if (closed) {
					closed.decl.end = event.line;
				}
			}
		}
	}
	return found;
}

/**
 * The brace-delimited extent of each block a line matching `opener` starts — a Swift `#Preview { … }` —
 * as 1-based inclusive lines; an opener whose block never closes runs to the end of the source.
 */
export function blockExtents(
	language: string,
	source: string,
	opener: RegExp,
): { start: number; end: number }[] | null {
	const syntax = SYNTAX[language];
	if (!syntax) {
		return null;
	}
	const lines = source.split("\n");
	const events = braceEvents(source, syntax);
	const extents: { start: number; end: number }[] = [];
	for (const [index, text] of lines.entries()) {
		if (!opener.test(text)) {
			continue;
		}
		const first = events.findIndex((event) => event.brace === "{" && event.line >= index + 1);
		if (first === -1) {
			continue;
		}
		let depth = 0;
		let end = lines.length;
		for (const event of events.slice(first)) {
			depth += event.brace === "{" ? 1 : -1;
			if (depth === 0) {
				end = event.line;
				break;
			}
		}
		extents.push({ start: index + 1, end });
	}
	return extents;
}

/** The innermost declaration a line lies in, or undefined at file scope. */
export function enclosingDeclaration(decls: Declaration[], line: number): Declaration | undefined {
	let best: Declaration | undefined;
	for (const d of decls) {
		if (line < d.start || line > d.end) {
			continue;
		}
		if (!best || d.start >= best.start) {
			best = d;
		}
	}
	return best;
}

/** Replace comments and string contents with spaces while retaining match offsets and line breaks. */
export function maskSource(language: string | null, source: string): string {
	if (language === null) {
		return source;
	}
	const syntax: Syntax | undefined =
		SYNTAX[language] ??
		(language === "python" || language === "ruby"
			? {
					declaration: /^$/u,
					lineComment: "#",
					strings: [
						{ open: '"""', close: '"""', escape: "\\" },
						{ open: "'''", close: "'''", escape: "\\" },
						{ open: '"', close: '"', escape: "\\" },
						{ open: "'", close: "'", escape: "\\" },
					],
				}
			: undefined);
	if (syntax === undefined) {
		return source;
	}
	// RegExp offsets count UTF-16 code units, so split by code unit, never spread by code point.
	const chars = source.split("");
	const blank = (start: number, end: number) => {
		for (let at = start; at < end; at += 1) {
			if (chars[at] !== "\n" && chars[at] !== "\r") {
				chars[at] = " ";
			}
		}
	};
	const maskLiteral = (from: number, literal: StringSyntax): number => {
		const end = skipString(source, from, literal, syntax.strings);
		const hashes = opensAt(source, from, literal) ?? 0;
		const raw = "#".repeat(hashes);
		const marker = interpolationOpener(literal, hashes, raw);
		if (marker === undefined || literal.interpolation === undefined) {
			blank(from, end);
			return end;
		}
		let cursor = from + hashes + literal.open.length;
		let segment = from;
		while (cursor < end) {
			if (source.startsWith(marker, cursor)) {
				const [, openBracket, closeBracket] = literal.interpolation;
				const bodyStart = cursor + marker.length;
				const close = skipBalanced(source, bodyStart, openBracket, closeBracket, syntax.strings);
				const bodyEnd = close - closeBracket.length;
				blank(segment, bodyStart);
				const expression = maskSource(language, source.slice(bodyStart, bodyEnd));
				for (let index = 0; index < expression.length; index += 1) {
					chars[bodyStart + index] = expression.charAt(index);
				}
				segment = bodyEnd;
				cursor = close;
			} else if (literal.escape !== undefined && source.startsWith(literal.escape + raw, cursor)) {
				cursor += literal.escape.length + raw.length + 1;
			} else {
				cursor += 1;
			}
		}
		blank(segment, end);
		return end;
	};
	let at = 0;
	while (at < source.length) {
		if (source.startsWith(syntax.lineComment, at)) {
			const end = source.indexOf("\n", at);
			const next = end === -1 ? source.length : end;
			blank(at, next);
			at = next;
			continue;
		}
		if (syntax.blockComment !== undefined && source.startsWith(syntax.blockComment[0], at)) {
			const [open, close] = syntax.blockComment;
			let end = at + open.length;
			let depth = 1;
			while (end < source.length && depth > 0) {
				if (syntax.nestedBlockComments === true && source.startsWith(open, end)) {
					depth += 1;
					end += open.length;
				} else if (source.startsWith(close, end)) {
					depth -= 1;
					end += close.length;
				} else {
					end += 1;
				}
			}
			blank(at, end);
			at = end;
			continue;
		}
		const literal = stringOpeningAt(source, at, syntax.strings);
		if (literal !== undefined) {
			at = maskLiteral(at, literal);
			continue;
		}
		at += 1;
	}
	return chars.join("");
}
