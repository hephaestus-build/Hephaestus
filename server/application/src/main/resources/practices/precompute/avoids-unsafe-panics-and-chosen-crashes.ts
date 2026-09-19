// Precompute HINTS for avoids-unsafe-panics-and-chosen-crashes: locate deliberate-crash constructs ADDED
// in the diff, across languages. These are CANDIDATES to investigate — the LLM decides whether each is a
// real, unsafe crash a realistic input/state can trigger. General by design: a per-language pattern table
// keyed off the file extension, NOT a Swift-only scan. Adding a language = adding a row, no engine change.
import { isCommentLine } from "../lib/declarations.ts";
import { languageOf } from "../lib/languages.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// language key -> [human label, regex] of deliberate-crash / force-unwrap constructs in ADDED code.
const LANG_PATTERNS: Record<string, Array<[string, RegExp]>> = {
	swift: [
		["try!", /\btry!/],
		["fatalError", /\bfatalError\s*\(/],
		["force-cast as!", /\bas!\s/],
		["preconditionFailure", /\bpreconditionFailure\s*\(/],
		["assertionFailure", /\bassertionFailure\s*\(/],
		["force-unwrap", /[A-Za-z0-9_)\]]!(\.|\s|$|\))/],
		// The closed list the criteria decide the occasion by continues with the traps that are not
		// spelled with a bang: a subscript whose index is not a literal, a lossy numeric conversion of
		// a runtime value, and a division or modulo by a non-literal.
		["subscript with a non-literal index", /[A-Za-z_][A-Za-z0-9_.]*\[\s*[A-Za-z_(][^\]\n]*\]/],
		["lossy numeric conversion", /\b(U?Int(8|16|32|64)?)\(\s*[A-Za-z_(]/],
		["division or modulo by a non-literal", /\S\s[/%]\s[A-Za-z_(]/],
	],
	typescript: [
		["process.exit", /\bprocess\.exit\s*\(/],
		["non-null assertion", /[A-Za-z0-9_)\]]![.;)\s]/],
	],
	javascript: [["process.exit", /\bprocess\.exit\s*\(/]],
	python: [
		["sys.exit", /\bsys\.exit\s*\(/],
		["os._exit", /\bos\._exit\s*\(/],
		["raise SystemExit", /\braise\s+SystemExit\b/],
		["bare assert in code", /^\s*assert\s+/],
	],
	go: [
		["panic(", /\bpanic\s*\(/],
		["log.Fatal", /\blog\.Fatal[a-z]*\s*\(/],
		["os.Exit", /\bos\.Exit\s*\(/],
	],
	java: [
		["System.exit", /\bSystem\.exit\s*\(/],
		["throw AssertionError", /\bthrow\s+new\s+AssertionError\b/],
		["Optional.get()", /\bOptional[^;]*\.get\s*\(\s*\)/],
	],
	kotlin: [
		["!! force non-null", /!!(\.|\s|$|\))/],
		["error(", /(^|[^.\w])error\s*\(/],
		["TODO(", /\bTODO\s*\(/],
	],
	rust: [
		[".unwrap()", /\.unwrap\s*\(\s*\)/],
		[".expect(", /\.expect\s*\(/],
		["panic!", /\bpanic!\s*\(/],
		["unreachable!", /\bunreachable!\s*\(/],
		["unimplemented!/todo!", /\b(unimplemented|todo)!\s*\(/],
	],
	ruby: [
		["exit!", /\bexit!/],
		["abort", /\babort\b/],
	],
	c: [
		["abort(", /\babort\s*\(/],
		["exit(", /\bexit\s*\(/],
		["assert(", /\bassert\s*\(/],
	],
	// Objective-C is C with Cocoa's assertion macros, not Swift: no bang operators to find.
	"objective-c": [
		["abort(", /\babort\s*\(/],
		["exit(", /\bexit\s*\(/],
		["NSAssert", /\bNS(?:C)?Assert\s*\(/],
	],
	csharp: [
		["Environment.Exit", /\bEnvironment\.Exit\s*\(/],
		["Environment.FailFast", /\bEnvironment\.FailFast\s*\(/],
		["Debug.Assert", /\bDebug\.Assert\s*\(/],
		["throw new", /\bthrow\s+new\s+\w+/],
		["null-forgiving !", /[A-Za-z0-9_)\]]!\.(?=[A-Za-z_])/],
	],
};

export default function avoidsUnsafePanicsAndChosenCrashes(
	_repo: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	const byLang: Record<string, number> = {};
	for (const [path, df] of diffFiles) {
		const lang = languageOf(path);
		if (!lang) continue;
		const patterns = LANG_PATTERNS[lang];
		if (!patterns) continue;
		for (const [line, content] of df.addedLines) {
			if (isCommentLine(content, lang)) continue;
			for (const [name, re] of patterns) {
				if (re.test(content)) {
					hints.push({
						file: path,
						line,
						pattern: `${lang}:${name}`,
						context: content.trim().slice(0, 160),
						inDiff: true,
						flags: {},
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
					`Found ${hints.length} deliberate-crash / force-unwrap construct(s) added across ${Object.keys(byLang).length} language(s) — investigate whether each can be triggered by realistic input/state and should handle the failure instead of crashing.`,
				]
			: [];
	return {
		hints: hints.slice(0, 40),
		metrics: { crashConstructsAdded: hints.length, ...byLang },
		directions,
	};
}
