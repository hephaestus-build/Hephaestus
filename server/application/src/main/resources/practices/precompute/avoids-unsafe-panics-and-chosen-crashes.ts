// Precompute HINTS for avoids-unsafe-panics-and-chosen-crashes: locate deliberate-crash constructs ADDED
// in the diff, across languages. These are CANDIDATES to investigate — the LLM decides whether each is a
// real, unsafe crash a realistic input/state can trigger. General by design: a per-language pattern table
// keyed off the file extension, NOT a Swift-only scan. Adding a language = adding a row, no engine change.
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
	ts: [
		["process.exit", /\bprocess\.exit\s*\(/],
		["non-null assertion", /[A-Za-z0-9_)\]]![.;)\s]/],
	],
	js: [["process.exit", /\bprocess\.exit\s*\(/]],
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
	csharp: [
		["Environment.Exit", /\bEnvironment\.Exit\s*\(/],
		["Environment.FailFast", /\bEnvironment\.FailFast\s*\(/],
		["Debug.Assert", /\bDebug\.Assert\s*\(/],
		["throw new", /\bthrow\s+new\s+\w+/],
		["null-forgiving !", /[A-Za-z0-9_)\]]!\.(?=[A-Za-z_])/],
	],
};

// extension -> language key. One source of truth for both pattern lookup and comment syntax.
const EXT_LANG: Record<string, string> = {
	swift: "swift",
	m: "swift",
	mm: "swift",
	ts: "ts",
	tsx: "ts",
	mts: "ts",
	cts: "ts",
	js: "js",
	jsx: "js",
	mjs: "js",
	cjs: "js",
	py: "python",
	go: "go",
	java: "java",
	kt: "kotlin",
	kts: "kotlin",
	rs: "rust",
	rb: "ruby",
	c: "c",
	h: "c",
	cc: "c",
	cpp: "c",
	cxx: "c",
	hpp: "c",
	cs: "csharp",
};

function langOf(path: string): string | null {
	const ext = path.split(".").pop()?.toLowerCase() ?? "";
	return EXT_LANG[ext] ?? null;
}

// Strip the obvious comment forms so we don't flag a pattern that only appears inside a comment.
function isComment(trimmed: string): boolean {
	return (
		trimmed.startsWith("//") ||
		trimmed.startsWith("#") ||
		trimmed.startsWith("*") ||
		trimmed.startsWith("/*")
	);
}

export default function avoidsUnsafePanicsAndChosenCrashes(
	_repo: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	const byLang: Record<string, number> = {};
	for (const [path, df] of diffFiles) {
		const lang = langOf(path);
		if (!lang) continue;
		const patterns = LANG_PATTERNS[lang];
		if (!patterns) continue;
		for (const [line, content] of df.addedLines) {
			const trimmed = content.trimStart();
			if (isComment(trimmed)) continue;
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
