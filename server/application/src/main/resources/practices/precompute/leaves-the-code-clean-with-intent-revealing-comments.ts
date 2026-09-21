// Precompute HINTS for leaves-the-code-clean-with-intent-revealing-comments. Surfaces facts only — the LLM
// judges whether each is real residue. On a large diff the model under-scans length and misses obvious
// debug traces / leftover markers sitting right there; this points it at the exact added lines. General by
// design: a per-language debug-output token table keyed off file extension. Adding a language = a row.
// CANDIDATES, never an observation.
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// language key -> [human label, regex] of debug-output / residue constructs that, when ADDED, are worth a look.
const LANG_PATTERNS: Record<string, [string, RegExp][]> = {
	swift: [
		["print(", /(?:^|[^.\w])print\s*\(/u],
		["debugPrint(", /\bdebugPrint\s*\(/u],
		["NSLog(", /\bNSLog\s*\(/u],
		["dump(", /(?:^|[^.\w])dump\s*\(/u],
	],
	ts: [
		["console.*", /\bconsole\.(?:log|debug|info|warn|error|trace)\s*\(/u],
		["debugger", /\bdebugger\b/u],
	],
	js: [
		["console.*", /\bconsole\.(?:log|debug|info|warn|error|trace)\s*\(/u],
		["debugger", /\bdebugger\b/u],
	],
	python: [
		["print(", /(?:^|[^.\w])print\s*\(/u],
		["breakpoint(", /\bbreakpoint\s*\(/u],
		["pprint(", /\bpprint\s*\(/u],
	],
	java: [
		["System.out/err.print", /\bSystem\.(?:out|err)\.print/u],
		["printStackTrace(", /\.printStackTrace\s*\(/u],
	],
	kotlin: [["println(", /\bprintln\s*\(/u]],
	go: [
		["fmt.Print*", /\bfmt\.Print[a-z]*\s*\(/u],
		["println(", /(?:^|[^.\w])println\s*\(/u],
	],
	ruby: [["puts/p/pp", /(?:^|[^.\w])(?:puts|pp?)\s+["'\d:@]/u]],
	rust: [["println!/dbg!/eprintln!", /\b(?:println|eprintln|dbg|print|eprint)\s*!/u]],
};

// A TODO/FIXME/XXX/HACK marker added in the diff — a residue signal across all languages.
const TODO_MARKER = /\b(?:TODO|FIXME|XXX|HACK)\b/u;

const EXT_TO_LANG: Record<string, string> = {
	swift: "swift",
	ts: "ts",
	tsx: "ts",
	js: "js",
	jsx: "js",
	mjs: "js",
	py: "python",
	java: "java",
	kt: "kotlin",
	go: "go",
	rb: "ruby",
	rs: "rust",
};

function langFor(path: string): string | null {
	const ext = (path.split(".").pop() ?? "").toLowerCase();
	return EXT_TO_LANG[ext] ?? null;
}

export default function leavesTheCodeCleanWithIntentRevealingComments(
	_repo: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	const byLang: Record<string, number> = {};
	let debugCandidates = 0;
	let todoCandidates = 0;

	for (const [path, df] of diffFiles) {
		const lang = langFor(path);
		const patterns = lang === null ? [] : (LANG_PATTERNS[lang] ?? []);
		for (const [lineNum, text] of df.addedLines) {
			// Skip lines that are themselves comments — a debug call inside a comment is not live residue.
			const trimmed = text.trim();
			const isComment = /^(?:\/\/|#|\*|\/\*)/u.test(trimmed);
			// One debug-output hint per added line — match the sibling validates-inputs script.
			const debugOutput = isComment ? undefined : patterns.find(([, re]) => re.test(text));
			if (debugOutput !== undefined) {
				hints.push({
					file: path,
					line: lineNum,
					pattern: debugOutput[0],
					context: trimmed.slice(0, 160),
					inDiff: true,
					flags: { kind: "debug-output" },
				});
				debugCandidates += 1;
				if (lang !== null) {
					byLang[lang] = (byLang[lang] ?? 0) + 1;
				}
			}
			if (TODO_MARKER.test(text)) {
				hints.push({
					file: path,
					line: lineNum,
					pattern: "TODO/FIXME marker",
					context: trimmed.slice(0, 160),
					inDiff: true,
					flags: { kind: "marker" },
				});
				todoCandidates += 1;
			}
		}
	}

	const directions: string[] = [];
	if (debugCandidates > 0) {
		directions.push(
			`Found ${debugCandidates} debug-output call(s) on added lines (print/console/log-style) — investigate whether each is leftover debug residue or a deliberate, intent-revealing log.`,
		);
	}
	if (todoCandidates > 0) {
		directions.push(
			`Found ${todoCandidates} TODO/FIXME/XXX/HACK marker(s) added — investigate whether each is actionable residue or an intentional, tracked note.`,
		);
	}
	return { hints, metrics: { debugCandidates, todoCandidates, ...byLang }, directions };
}
