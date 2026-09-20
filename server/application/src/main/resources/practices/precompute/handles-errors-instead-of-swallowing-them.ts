// Precompute HINTS for handles-errors-instead-of-swallowing-them: locate error-discarding / swallowing
// constructs in ADDED code, across languages. CANDIDATES only — the LLM decides whether the error is
// genuinely swallowed (no surfacing, logging, or recovery). Empty/no-op catch bodies routinely span lines
// (`catch {\n}`), so patterns run over a window of CONSECUTIVE added lines per file, NOT a single line.
// General by design: a per-language pattern table keyed off the file extension. Adding a language = a row.
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// language key -> [human label, regex] of error-discarding / swallowing constructs in ADDED code. The `\s`
// in each regex spans the newlines joined into a window, so multi-line empty bodies match.
const LANG_PATTERNS: Record<string, [string, RegExp][]> = {
	swift: [
		["try? (discards error)", /\btry\?\s/u],
		["empty catch", /\bcatch\s*\{\s*\}/u],
		["catch with only print", /\bcatch\s*\{\s*print\s*\([^)]*\)\s*\}/u],
		["error ignored: if let error {}", /\bif\s+let\s+error\b[^{]*\{\s*\}/u],
	],
	ts: [
		["empty catch", /\bcatch\s*(?:\([^)]*\))?\s*\{\s*\}/u],
		[".catch(noop)", /\.catch\s*\(\s*\(\s*[^)]*\)\s*=>\s*\{\s*\}\s*\)/u],
		[
			"catch with only console",
			/\bcatch\s*(?:\([^)]*\))?\s*\{\s*console\.[a-z]+\([^)]*\)\s*;?\s*\}/u,
		],
	],
	js: [
		["empty catch", /\bcatch\s*(?:\([^)]*\))?\s*\{\s*\}/u],
		[".catch(noop)", /\.catch\s*\(\s*\(\s*[^)]*\)\s*=>\s*\{\s*\}\s*\)/u],
		[
			"catch with only console",
			/\bcatch\s*(?:\([^)]*\))?\s*\{\s*console\.[a-z]+\([^)]*\)\s*;?\s*\}/u,
		],
	],
	python: [
		["except: pass", /\bexcept[^:]*:\s*pass\b/u],
		["bare except", /^\s*except\s*:/mu],
		["except ...: continue", /\bexcept[^:]*:\s*continue\b/u],
	],
	go: [
		[
			"blank-identifier assignment (possible ignored error)",
			/\b_\s*(?:,\s*[a-zA-Z0-9_]+)?\s*:?=\s*[a-zA-Z_][a-zA-Z0-9_.]*\s*\(/u,
		],
		["empty if err != nil", /\bif\s+err\s*!=\s*nil\s*\{\s*\}/u],
	],
	java: [
		["empty catch", /\bcatch\s*\([^)]*\)\s*\{\s*\}/u],
		[
			"catch printStackTrace only",
			/\bcatch\s*\([^)]*\)\s*\{\s*[a-zA-Z0-9_.]*\.printStackTrace\s*\(\s*\)\s*;?\s*\}/u,
		],
	],
	kotlin: [
		["empty catch", /\bcatch\s*\([^)]*\)\s*\{\s*\}/u],
		["runCatching{}.getOrNull", /runCatching\s*\{[\s\S]*?\}\s*\.getOrNull\s*\(\s*\)/u],
	],
	rust: [
		[".ok(); (discards Err)", /\.ok\s*\(\s*\)\s*;/u],
		["let _ = call()", /\blet\s+_\s*=\s*[a-zA-Z_][a-zA-Z0-9_.:]*\s*\(/u],
		["if let Err(_) {} empty", /\bif\s+let\s+Err\s*\(\s*_\s*\)[^{]*\{\s*\}/u],
	],
	ruby: [
		["rescue => e (unused)", /\brescue\s*=>\s*[a-z_]+\s*$/mu],
		["rescue; end", /\brescue\b[^;\n]*;\s*end\b/u],
	],
};

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
};

function langOf(path: string): string | null {
	const ext = path.split(".").pop()?.toLowerCase() ?? "";
	return EXT_LANG[ext] ?? null;
}

function isComment(trimmed: string): boolean {
	return (
		trimmed.startsWith("//") ||
		trimmed.startsWith("#") ||
		trimmed.startsWith("*") ||
		trimmed.startsWith("/*")
	);
}

// Group a file's added lines into windows of CONSECUTIVE line numbers so a multi-line construct
// (e.g. `catch {` then `}`) is one searchable text block without falsely joining distant additions.
function consecutiveWindows(added: Map<number, string>): { start: number; lines: string[] }[] {
	const windows: { start: number; lines: string[] }[] = [];
	// The open window carries its own first and last line number, so "is there a window" and "what does
	// it end at" are one nullable value rather than length checks against a scratch array.
	let open: { start: number; end: number; lines: string[] } | null = null;
	for (const n of [...added.keys()].toSorted((a, b) => a - b)) {
		if (open !== null && n !== open.end + 1) {
			windows.push({ start: open.start, lines: open.lines });
			open = null;
		}
		open ??= { start: n, end: n, lines: [] };
		open.end = n;
		open.lines.push(added.get(n) ?? "");
	}
	if (open !== null) {
		windows.push({ start: open.start, lines: open.lines });
	}
	return windows;
}

export default function handlesErrorsInsteadOfSwallowingThem(
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
		for (const w of consecutiveWindows(df.addedLines)) {
			const text = w.lines.join("\n");
			for (const [name, re] of patterns) {
				const match = text.match(re);
				if (!match || match.index === undefined) {
					continue;
				}
				const offset = text.slice(0, match.index).split("\n").length - 1;
				const lineNum = w.start + offset;
				const lineContent = w.lines[offset] ?? "";
				if (isComment(lineContent.trimStart())) {
					continue;
				}
				hints.push({
					file: path,
					line: lineNum,
					pattern: `${lang}:${name}`,
					context: lineContent.trim().slice(0, 160),
					inDiff: true,
					flags: {},
				});
				byLang[lang] = (byLang[lang] ?? 0) + 1;
				// one hint per window — the LLM reads the full diff for the rest
				break;
			}
		}
	}
	const directions =
		hints.length > 0
			? [
					`Found ${hints.length} construct(s) added across ${Object.keys(byLang).length} language(s) that may discard or silence an error — investigate whether the failure is genuinely swallowed (no surfacing, logging, or recovery).`,
				]
			: [];
	return {
		hints: hints.slice(0, 40),
		metrics: { errorSwallowCandidates: hints.length, ...byLang },
		directions,
	};
}
