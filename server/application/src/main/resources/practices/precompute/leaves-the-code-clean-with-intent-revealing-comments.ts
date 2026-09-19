// Precompute HINTS for leaves-the-code-clean-with-intent-revealing-comments. Surfaces facts only — the LLM
// judges whether each is real residue. On a large diff the model under-scans length and misses obvious
// debug traces / leftover markers sitting right there; this points it at the exact added lines. General by
// design: a per-language debug-output token table keyed off file extension. Adding a language = a row.
// CANDIDATES, never an observation.
import { languageOf } from "../lib/languages.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// language key -> [human label, regex] of debug-output / residue constructs that, when ADDED, are worth a look.
const LANG_PATTERNS: Record<string, Array<[string, RegExp]>> = {
	swift: [
		["print(", /(^|[^.\w])print\s*\(/],
		["debugPrint(", /\bdebugPrint\s*\(/],
		["NSLog(", /\bNSLog\s*\(/],
		["dump(", /(^|[^.\w])dump\s*\(/],
	],
	typescript: [
		["console.*", /\bconsole\.(log|debug|info|warn|error|trace)\s*\(/],
		["debugger", /\bdebugger\b/],
	],
	javascript: [
		["console.*", /\bconsole\.(log|debug|info|warn|error|trace)\s*\(/],
		["debugger", /\bdebugger\b/],
	],
	python: [
		["print(", /(^|[^.\w])print\s*\(/],
		["breakpoint(", /\bbreakpoint\s*\(/],
		["pprint(", /\bpprint\s*\(/],
	],
	java: [
		["System.out/err.print", /\bSystem\.(out|err)\.print/],
		["printStackTrace(", /\.printStackTrace\s*\(/],
	],
	kotlin: [["println(", /\bprintln\s*\(/]],
	go: [
		["fmt.Print*", /\bfmt\.Print[a-z]*\s*\(/],
		["println(", /(^|[^.\w])println\s*\(/],
	],
	ruby: [["puts/p/pp", /(^|[^.\w])(puts|pp?)\s+["'\d:@]/]],
	rust: [["println!/dbg!/eprintln!", /\b(println|eprintln|dbg|print|eprint)\s*!/]],
};

// A TODO/FIXME/XXX/HACK marker added in the diff — a residue signal across all languages.
const TODO_MARKER = /\b(TODO|FIXME|XXX|HACK)\b/;

// A comment that narrates the editing session rather than the code, on any language's comment line.
const PROCESS_NOTE =
	/^\s*(?:\/\/|#|\*)\s*(?:FIX(?:ED)?\s*\d*\s*:|CHANGED?\s*:|ADDED\s*:|MOVED\s*:|UPDATED?\s*:|REMOVED?\s*:|NEW\s*:)/i;
// A template placeholder left in a manifest the change edited.
const TEMPLATE_PLACEHOLDER = /#\s*TODO:\s*Adjust|<your[- ]|REPLACE[_ ]ME|CHANGE[_ ]ME/i;

export default function leavesTheCodeCleanWithIntentRevealingComments(
	_repo: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	const byLang: Record<string, number> = {};
	let debugCandidates = 0;
	let todoCandidates = 0;
	let processNotes = 0;
	let placeholders = 0;
	let scratchFiles = 0;

	for (const [path, df] of diffFiles) {
		const lang = languageOf(path);
		const patterns = lang ? (LANG_PATTERNS[lang] ?? []) : [];
		// A file added with a line or two of content and no language is a scratch file until read.
		const added = [...df.addedLines.values()].filter((l) => l.trim().length > 0);
		if (
			!lang &&
			df.removedLines.size === 0 &&
			added.length > 0 &&
			added.length <= 2 &&
			/\.(txt|md)$/i.test(path) &&
			!/README|CHANGELOG|LICENSE/i.test(path)
		) {
			hints.push({
				file: path,
				line: [...df.addedLines.keys()][0] ?? 1,
				pattern: "near-empty new file",
				context: added[0]?.trim().slice(0, 160) ?? "",
				inDiff: true,
				flags: { kind: "scratch" },
			});
			scratchFiles++;
		}
		for (const [lineNum, text] of df.addedLines) {
			// Skip lines that are themselves comments — a debug call inside a comment is not live residue.
			const trimmed = text.trim();
			const isComment = /^(\/\/|#|\*|\/\*)/.test(trimmed);
			for (const [label, re] of patterns) {
				if (!isComment && re.test(text)) {
					hints.push({
						file: path,
						line: lineNum,
						pattern: label,
						context: trimmed.slice(0, 160),
						inDiff: true,
						flags: { kind: "debug-output" },
					});
					debugCandidates++;
					if (lang) byLang[lang] = (byLang[lang] ?? 0) + 1;
					break; // one debug-output hint per added line — match the sibling validates-inputs script
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
				todoCandidates++;
			}
			if (PROCESS_NOTE.test(text)) {
				hints.push({
					file: path,
					line: lineNum,
					pattern: "editing-process note",
					context: trimmed.slice(0, 160),
					inDiff: true,
					flags: { kind: "process-note" },
				});
				processNotes++;
			}
			if (TEMPLATE_PLACEHOLDER.test(text)) {
				hints.push({
					file: path,
					line: lineNum,
					pattern: "template placeholder",
					context: trimmed.slice(0, 160),
					inDiff: true,
					flags: { kind: "placeholder" },
				});
				placeholders++;
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
	if (processNotes + placeholders + scratchFiles > 0) {
		directions.push(
			`Found ${processNotes} comment(s) narrating the editing session, ${placeholders} template placeholder(s) and ${scratchFiles} near-empty new file(s) — residue of the writing process unless the surrounding text says otherwise.`,
		);
	}
	return {
		hints,
		metrics: {
			debugCandidates,
			todoCandidates,
			processNotes,
			placeholders,
			scratchFiles,
			...byLang,
		},
		directions,
	};
}
