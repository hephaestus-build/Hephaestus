// Precompute FACTS for validates-inputs-and-edge-cases-at-the-boundary: the added lines of source code
// that hold a construct which can fail or mislead on an edge value — a subscript or slice, a division by
// a name, a parse the language does not force the author to handle, an extreme sentinel, a payload
// decode, a request's own input — across languages. A row marks where a consuming site may be, never a
// finding: whether its value comes from outside the local logic, and what already handles its bad case,
// is read from the code, and the criteria decide. String-literal text and comments are not scanned; a
// lookup or parse whose result the language makes optional is not listed, and a crash operator the author
// wrote (`!`, `try!`, `unwrap()`) is the crash practice's. Adding a language = adding a row.
import { codeLines, isCommentLine } from "../lib/declarations.ts";
import { isTestPath, languageOf } from "../lib/languages.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

const COMMON: [string, RegExp][] = [
	// `items[i]`, `cast[0..<2]`; not a lookup already made safe (`dict[k] ?? d`, `[k, default: 0]`,
	// `ns[k] as? T`) nor an assignment target.
	[
		"subscript or slice",
		/[A-Za-z0-9_)\]]\[(?![^\]]*\bdefault:)[^\]]+\](?!\s*(?:\?\?|as\?|=(?!=)))/u,
	],
	// A divisor that is a name or a call; a literal divisor cannot be zero.
	["division or remainder by a name", /[A-Za-z0-9_)\]]\s*[/%](?![/*=])\s*[A-Za-z_(]/u],
	// The seed of a minimum, a maximum or a fallback that can reach output unchanged.
	[
		"extreme sentinel",
		/\b(?:U?Int\d*|Double|Float|CGFloat)\.(?:max|min|infinity|greatestFiniteMagnitude)\b|\b(?:Integer|Long|Double|Float)\.(?:MAX|MIN)_VALUE\b|\bNumber\.(?:MAX|MIN)_(?:SAFE_INTEGER|VALUE)\b|\bsys\.maxsize\b|\bmath\.inf\b/u,
	],
];

// language key -> [label, regex] of edge constructs in added code, beside COMMON.
const LANG_PATTERNS: Record<string, [string, RegExp][]> = {
	// `Int(text)`, `Double(s)`, `.first`, `dict[key]` return Optionals the compiler makes the author handle.
	swift: [["payload decode", /(?<![Cc]ontainer)\.decode\s*\(/u]],
	typescript: [
		["parseInt/parseFloat", /\bparse(?:Int|Float)\s*\(/u],
		["Number(...) parse", /\bNumber\s*\(/u],
		["JSON.parse", /\bJSON\.parse\s*\(/u],
	],
	javascript: [
		["parseInt/parseFloat", /\bparse(?:Int|Float)\s*\(/u],
		["Number(...) parse", /\bNumber\s*\(/u],
		["JSON.parse", /\bJSON\.parse\s*\(/u],
	],
	python: [
		["int()/float() parse", /\b(?:int|float)\s*\(/u],
		["json.loads", /\bjson\.loads\s*\(/u],
		[".pop()", /\.pop\s*\(/u],
		["next(...)", /\bnext\s*\(/u],
	],
	go: [
		["strconv parse", /\bstrconv\.(?:Atoi|Parse[A-Za-z]+)\s*\(/u],
		["json.Unmarshal", /\bjson\.Unmarshal\s*\(/u],
	],
	java: [
		[".get(i) access", /\.get\s*\(\s*[A-Za-z_]/u],
		["Integer/Long/Double.parse", /\b(?:Integer|Long|Double|Float)\.parse[A-Za-z]+\s*\(/u],
		["valueOf parse", /\b(?:Integer|Long|Double|Float)\.valueOf\s*\(/u],
		[".charAt/.substring", /\.(?:charAt|substring)\s*\(/u],
	],
	kotlin: [
		["toInt/toDouble parse", /\.to(?:Int|Long|Double|Float)\s*\(\s*\)/u],
		[".first()/.last()", /\.(?:first|last)\s*\(\s*\)/u],
	],
	rust: [],
	ruby: [
		["JSON.parse", /\bJSON\.parse\s*\(/u],
		[".fetch", /\.fetch\s*\(/u],
	],
	c: [
		["atoi/strtol parse", /\b(?:atoi|atol|atof|strtol|strtod)\s*\(/u],
		["pointer deref *", /(?:^|[^\w)])\*[A-Za-z_]/u],
	],
	csharp: [
		[
			"int.Parse/Convert",
			/\b(?:int|long|double|float|decimal|Int32|Int64|Convert)\.(?:Parse|To[A-Za-z]+)\s*\(/u,
		],
		["JsonSerializer.Deserialize", /\.Deserialize\s*\(/u],
		[".First()/.Last()/.Single()", /\.(?:First|Last|Single)\s*\(\s*\)/u],
	],
};

// Where a request's own input enters a handler: the criteria's "a request param, body or header".
const REQUEST_INPUT: Record<string, RegExp> = {
	java: /@(?:RequestParam|PathVariable|RequestBody|RequestHeader)\b/u,
	kotlin: /@(?:RequestParam|PathVariable|RequestBody|RequestHeader)\b/u,
	typescript: /\breq(?:uest)?\.(?:params|query|body|headers)\b/u,
	javascript: /\breq(?:uest)?\.(?:params|query|body|headers)\b/u,
	python: /\brequest\.(?:args|form|json|GET|POST|query_params|data)\b/u,
	go: /\br\.(?:URL\.Query|FormValue|PostFormValue|PathValue)\b/u,
	swift: /\breq\.(?:parameters|query|content)\b/u,
};

export default function validatesInputsAndEdgeCasesAtTheBoundary(
	_repo: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	const byKind: Record<string, number> = {};
	let filesScanned = 0;
	let linesScanned = 0;

	for (const [path, df] of diffFiles) {
		const lang = languageOf(path);
		const own = lang === null ? undefined : LANG_PATTERNS[lang];
		// A test's fixtures are not a boundary.
		if (lang === null || !own || isTestPath(path)) {
			continue;
		}
		const request = REQUEST_INPUT[lang];
		const patterns: [string, RegExp][] =
			request === undefined ? [...COMMON, ...own] : [["request input", request], ...COMMON, ...own];
		const code = codeLines(lang, df.addedLines);
		filesScanned += 1;
		for (const [line, content] of df.addedLines) {
			if (isCommentLine(content, lang)) {
				continue;
			}
			linesScanned += 1;
			const labels = patterns
				.filter(([, re]) => re.test(code.get(line) ?? ""))
				.map(([name]) => name);
			if (labels.length === 0) {
				continue;
			}
			for (const label of labels) {
				byKind[label] = (byKind[label] ?? 0) + 1;
			}
			hints.push({
				file: path,
				line,
				pattern: `${lang}:${labels.join(" + ")}`,
				context: content.trim().slice(0, 160),
				inDiff: true,
				flags: {},
			});
		}
	}

	const kinds = Object.entries(byKind)
		.map(([kind, count]) => `${String(count)} ${kind}`)
		.join(", ");
	const directions =
		hints.length > 0
			? [
					`${hints.length} added line(s) hold a construct that can fail or mislead on an edge value (${kinds}). String-literal text, comments and test files were not scanned; lookups and parses whose result the language makes optional, and crash operators, are not listed. A listed line marks where a consuming site may be, not a finding: whether its value comes from outside the local logic, and what already handles its bad case, is read from the code.`,
				]
			: [];

	return {
		hints: hints.slice(0, 40),
		metrics: {
			edgeConstructLines: hints.length,
			filesScanned,
			linesScanned,
			...byKind,
		},
		directions,
	};
}
