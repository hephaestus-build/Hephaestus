// Precompute HINTS for validates-inputs-and-edge-cases-at-the-boundary: locate boundary/edge CANDIDATE
// sites ADDED in the diff, across languages. These are places where a missing index/null/parse/division
// guard could live — the LLM decides whether a guard is actually present. General by design: a per-language
// pattern table keyed off the file extension, NOT a single-language scan. Adding a language = adding a row,
// no engine change. NO observation, severity, or "defect" — facts only.
import { isCommentLine } from "../lib/declarations.ts";
import { languageOf } from "../lib/languages.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// Cross-language boundary/edge constructs. Most are language-agnostic enough to share, but the table is
// keyed per-language so a row can be tuned without affecting others.
const COMMON: [string, RegExp][] = [
	// indexed access into an array/collection — a[i], a[i + 1], etc. (not a[0]-style obvious literals only:
	// we still surface; the LLM judges). Skip pure type subscripts is best-effort via the digit/ident guard.
	["index access [..]", /[A-Za-z0-9_)\]]\[[^\]]+\]/u],
	// division (potential divide-by-zero) — "/ x" not "//" comment and not "/*".
	["division /", /[A-Za-z0-9_)\]]\s*\/(?![/*])\s*[A-Za-z0-9_(]/u],
	// modulo (same zero-divisor edge)
	["modulo %", /[A-Za-z0-9_)\]]\s*%\s*[A-Za-z0-9_(]/u],
];

// language key -> [human label, regex] of boundary/edge candidate constructs in ADDED code.
const LANG_PATTERNS: Record<string, [string, RegExp][]> = {
	swift: [
		...COMMON,
		["force-unwrap !", /[A-Za-z0-9_)\]]!(?:\.|\s|$|\))/u],
		["try!", /\btry!/u],
		["Int(...) parse", /\bU?Int\d*\s*\(/u],
		["Double/Float(...) parse", /\b(?:Double|Float)\s*\(/u],
		["JSONDecoder.decode", /\.decode\s*\(/u],
		[".first/.last", /\.(?:first|last)\b/u],
	],
	typescript: [
		...COMMON,
		["non-null assertion !", /[A-Za-z0-9_)\]]![.;)\s]/u],
		["parseInt/parseFloat", /\bparse(?:Int|Float)\s*\(/u],
		["Number(...) parse", /\bNumber\s*\(/u],
		["JSON.parse", /\bJSON\.parse\s*\(/u],
		[".get(...) lookup", /\.get\s*\(/u],
	],
	javascript: [
		...COMMON,
		["parseInt/parseFloat", /\bparse(?:Int|Float)\s*\(/u],
		["Number(...) parse", /\bNumber\s*\(/u],
		["JSON.parse", /\bJSON\.parse\s*\(/u],
		[".get(...) lookup", /\.get\s*\(/u],
	],
	python: [
		...COMMON,
		["int()/float() parse", /\b(?:int|float)\s*\(/u],
		["json.loads", /\bjson\.loads\s*\(/u],
		[".pop()/[index]", /\.pop\s*\(/u],
		["next(...)", /\bnext\s*\(/u],
	],
	go: [
		...COMMON,
		["strconv parse", /\bstrconv\.(?:Atoi|Parse[A-Za-z]+)\s*\(/u],
		["json.Unmarshal", /\bjson\.Unmarshal\s*\(/u],
		["map/slice index", /\[[^\]]+\]/u],
	],
	java: [
		...COMMON,
		[".get(i) access", /\.get\s*\(/u],
		["Integer/Long/Double.parse", /\b(?:Integer|Long|Double|Float)\.parse[A-Za-z]+\s*\(/u],
		["valueOf parse", /\b(?:Integer|Long|Double|Float)\.valueOf\s*\(/u],
		["Optional.get()", /\bOptional[^;]*\.get\s*\(\s*\)/u],
		[".charAt/.substring", /\.(?:charAt|substring)\s*\(/u],
	],
	kotlin: [
		...COMMON,
		["!! force non-null", /!!(?:\.|\s|$|\))/u],
		["toInt/toDouble parse", /\.to(?:Int|Long|Double|Float)\s*\(/u],
		[".get(i) access", /\.get\s*\(/u],
		[".first()/.last()", /\.(?:first|last)\s*\(\s*\)/u],
	],
	rust: [
		...COMMON,
		[".unwrap()", /\.unwrap\s*\(\s*\)/u],
		[".expect(", /\.expect\s*\(/u],
		["parse::<>()", /\.parse\s*(?:::<[^>]+>)?\s*\(/u],
		["serde from_str", /\bfrom_str\s*\(/u],
	],
	ruby: [
		...COMMON,
		["to_i/to_f parse", /\.to_[if]\b/u],
		["JSON.parse", /\bJSON\.parse\s*\(/u],
		[".fetch/[index]", /\.fetch\s*\(/u],
	],
	c: [
		...COMMON,
		["atoi/strtol parse", /\b(?:atoi|atol|atof|strtol|strtod)\s*\(/u],
		["pointer deref *", /(?:^|[^\w)])\*[A-Za-z_]/u],
	],
	csharp: [
		...COMMON,
		["null-forgiving !", /[A-Za-z0-9_)\]]!\.(?=[A-Za-z_])/u],
		[
			"int.Parse/Convert",
			/\b(?:int|long|double|float|decimal|Int32|Int64|Convert)\.(?:Parse|To[A-Za-z]+)\s*\(/u,
		],
		["JsonSerializer.Deserialize", /\.Deserialize\s*\(/u],
		[".First()/.Last()/[i]", /\.(?:First|Last|Single)\s*\(/u],
	],
};

// Public/exported function signatures with at least one parameter — the parameters are the boundary the
// function must defend. We surface the signature site; the LLM checks whether the added body guards them.
const SIGNATURE_PATTERNS: Record<string, RegExp> = {
	swift: /\b(?:public|open)\s+func\s+\w+\s*\([^)]*[A-Za-z_][^)]*\)/u,
	typescript: /\bexport\s+(?:async\s+)?function\s+\w+\s*\([^)]*[A-Za-z_][^)]*\)/u,
	javascript: /\bexport\s+(?:async\s+)?function\s+\w+\s*\([^)]*[A-Za-z_][^)]*\)/u,
	python: /^\s*def\s+(?!_)\w+\s*\([^)]*[A-Za-z_][^)]*\)/u,
	go: /^func\s+(?:\([^)]*\)\s*)?[A-Z]\w*\s*\([^)]*[A-Za-z_][^)]*\)/u,
	java: /\bpublic\s+[\w<>[\],?\s]+\s+\w+\s*\([^)]*[A-Za-z_][^)]*\)/u,
	kotlin: /\b(?:public\s+)?fun\s+\w+\s*\([^)]*[A-Za-z_][^)]*\)/u,
	rust: /\bpub\s+(?:async\s+)?fn\s+\w+\s*(?:<[^>]*>)?\s*\([^)]*[A-Za-z_][^)]*\)/u,
	ruby: /^\s*def\s+(?!_)\w+\s*[(\s][^)]*[A-Za-z_]/u,
	c: /^[A-Za-z_][\w\s*]+\s+\w+\s*\([^)]*[A-Za-z_][^)]*\)\s*\{?$/u,
	csharp: /\bpublic\s+[\w<>[\],?\s]+\s+\w+\s*\([^)]*[A-Za-z_][^)]*\)/u,
};

export default function validatesInputsAndEdgeCasesAtTheBoundary(
	_repo: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	const byLang: Record<string, number> = {};
	let signatureSites = 0;
	let filesScanned = 0;
	let linesAdded = 0;

	for (const [path, df] of diffFiles) {
		const lang = languageOf(path);
		if (lang === null) {
			continue;
		}
		const patterns = LANG_PATTERNS[lang];
		if (!patterns) {
			continue;
		}
		const sigRe = SIGNATURE_PATTERNS[lang];
		filesScanned += 1;

		for (const [line, content] of df.addedLines) {
			if (isCommentLine(content, lang)) {
				continue;
			}
			linesAdded += 1;

			// Public/exported signature with parameters: the parameters are an unguarded-input candidate.
			if (sigRe?.test(content) === true) {
				hints.push({
					file: path,
					line,
					pattern: `${lang}:exported-fn-params`,
					context: content.trim().slice(0, 160),
					inDiff: true,
					flags: { kind: "parameter-boundary" },
				});
				byLang[lang] = (byLang[lang] ?? 0) + 1;
				signatureSites += 1;
				continue;
			}

			for (const [name, re] of patterns) {
				if (re.test(content)) {
					hints.push({
						file: path,
						line,
						pattern: `${lang}:${name}`,
						context: content.trim().slice(0, 160),
						inDiff: true,
						flags: { kind: "edge-candidate" },
					});
					byLang[lang] = (byLang[lang] ?? 0) + 1;
					break;
				}
			}
		}
	}

	const directions =
		hints.length > 0
			? [
					`Found ${hints.length} boundary/edge candidate site(s) added across ${Object.keys(byLang).length} language(s) (${signatureSites} of them public/exported-function parameter lists) — investigate whether each index/deref/parse/division has a visible guard for the empty, missing, malformed, or out-of-range case, or whether such a check is absent in the added code.`,
				]
			: [];

	return {
		hints: hints.slice(0, 40),
		metrics: {
			edgeCandidateSitesAdded: hints.length,
			exportedSignatureSites: signatureSites,
			filesScanned,
			linesAdded,
			...byLang,
		},
		directions,
	};
}
