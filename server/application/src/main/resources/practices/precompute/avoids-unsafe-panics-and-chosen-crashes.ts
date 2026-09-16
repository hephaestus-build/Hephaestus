// Precompute HINTS for avoids-unsafe-panics-and-chosen-crashes: locate deliberate-crash constructs ADDED
// in the diff, across languages. These are CANDIDATES to investigate — the LLM decides whether each is a
// real, unsafe crash a realistic input/state can trigger. General by design: a per-language pattern table
// keyed off the file extension, NOT a Swift-only scan. Adding a language = adding a row, no engine change.
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// language key -> [human label, regex] of deliberate-crash / force-unwrap constructs in ADDED code.
const LANG_PATTERNS: Record<string, [string, RegExp][]> = {
	swift: [
		["try!", /\btry!/u],
		["fatalError", /\bfatalError\s*\(/u],
		["force-cast as!", /\bas!\s/u],
		["preconditionFailure", /\bpreconditionFailure\s*\(/u],
		["assertionFailure", /\bassertionFailure\s*\(/u],
		["force-unwrap", /[A-Za-z0-9_)\]]!(?:\.|\s|$|\))/u],
	],
	ts: [
		["process.exit", /\bprocess\.exit\s*\(/u],
		["non-null assertion", /[A-Za-z0-9_)\]]![.;)\s]/u],
	],
	js: [["process.exit", /\bprocess\.exit\s*\(/u]],
	python: [
		["sys.exit", /\bsys\.exit\s*\(/u],
		["os._exit", /\bos\._exit\s*\(/u],
		["raise SystemExit", /\braise\s+SystemExit\b/u],
		["bare assert in code", /^\s*assert\s+/u],
	],
	go: [
		["panic(", /\bpanic\s*\(/u],
		["log.Fatal", /\blog\.Fatal[a-z]*\s*\(/u],
		["os.Exit", /\bos\.Exit\s*\(/u],
	],
	java: [
		["System.exit", /\bSystem\.exit\s*\(/u],
		["throw AssertionError", /\bthrow\s+new\s+AssertionError\b/u],
		["Optional.get()", /\bOptional[^;]*\.get\s*\(\s*\)/u],
	],
	kotlin: [
		["!! force non-null", /!!(?:\.|\s|$|\))/u],
		["error(", /(?:^|[^.\w])error\s*\(/u],
		["TODO(", /\bTODO\s*\(/u],
	],
	rust: [
		[".unwrap()", /\.unwrap\s*\(\s*\)/u],
		[".expect(", /\.expect\s*\(/u],
		["panic!", /\bpanic!\s*\(/u],
		["unreachable!", /\bunreachable!\s*\(/u],
		["unimplemented!/todo!", /\b(?:unimplemented|todo)!\s*\(/u],
	],
	ruby: [
		["exit!", /\bexit!/u],
		["abort", /\babort\b/u],
	],
	c: [
		["abort(", /\babort\s*\(/u],
		["exit(", /\bexit\s*\(/u],
		["assert(", /\bassert\s*\(/u],
	],
	csharp: [
		["Environment.Exit", /\bEnvironment\.Exit\s*\(/u],
		["Environment.FailFast", /\bEnvironment\.FailFast\s*\(/u],
		["Debug.Assert", /\bDebug\.Assert\s*\(/u],
		["throw new", /\bthrow\s+new\s+\w+/u],
		["null-forgiving !", /[A-Za-z0-9_)\]]!\.(?=[A-Za-z_])/u],
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
		if (lang === null) {
			continue;
		}
		const patterns = LANG_PATTERNS[lang];
		if (!patterns) {
			continue;
		}
		for (const [line, content] of df.addedLines) {
			const trimmed = content.trimStart();
			if (isComment(trimmed)) {
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
