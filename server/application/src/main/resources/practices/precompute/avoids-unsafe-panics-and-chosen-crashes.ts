// Precompute FACTS for avoids-unsafe-panics-and-chosen-crashes: the added lines that spell a crash
// operator the author wrote — a force unwrap, `try!`, a force cast, an abort — across languages, each
// with where it runs: inside a preview block or in a test file. A row matches spelling on one line; it
// does not know the operand's type, where the value came from, or what bounds it, and the criteria
// decide which rows are lapses. Implicit traps (a subscript, an integer division) are the input
// practice's, so they are not listed here. Adding a language = adding a row, no engine change.
import { readFile } from "node:fs/promises";
import nodePath from "node:path";

import { blockExtents, codeLines, isCommentLine } from "../lib/declarations.ts";
import { isTestPath, languageOf } from "../lib/languages.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// language key -> [label, regex] of crash operators the author writes, matched against the line's code.
const LANG_PATTERNS: Record<string, [string, RegExp][]> = {
	swift: [
		["try!", /\btry!/u],
		["fatalError", /\bfatalError\s*\(/u],
		["force cast as!", /\bas!\s/u],
		[
			"precondition or assert",
			/\b(?:precondition|preconditionFailure|assert|assertionFailure)\s*\(/u,
		],
		["exit or abort", /\b(?:exit|abort)\s*\(/u],
		// `try!` and `as!` have their own rows.
		["force unwrap", /[A-Za-z0-9_)\]](?<!\btry|\bas)!(?:[.\s),\]]|$)/u],
	],
	typescript: [
		["process.exit", /\bprocess\.exit\s*\(/u],
		["non-null assertion", /[A-Za-z0-9_)\]]!(?:[.;)\s,\]]|$)/u],
	],
	javascript: [["process.exit", /\bprocess\.exit\s*\(/u]],
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
		["!! force non-null", /!!(?:[.\s)]|$)/u],
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
	// Objective-C is C with Cocoa's assertion macros, not Swift: no bang operators to find.
	"objective-c": [
		["abort(", /\babort\s*\(/u],
		["exit(", /\bexit\s*\(/u],
		["NSAssert", /\bNS(?:C)?Assert\s*\(/u],
	],
	csharp: [
		["Environment.Exit", /\bEnvironment\.Exit\s*\(/u],
		["Environment.FailFast", /\bEnvironment\.FailFast\s*\(/u],
		["Debug.Assert", /\bDebug\.Assert\s*\(/u],
		["null-forgiving !", /[A-Za-z0-9_)\]]!\.(?=[A-Za-z_])/u],
	],
};

// Code that runs only when Xcode renders a preview, never on a user's device.
const PREVIEW_OPENER = /^\s*(?:#Preview\b|(?:\w+\s+)*struct\s+\w+\s*:\s*PreviewProvider\b)/u;

/** The new side as far as the hunks show it: context and added lines at their numbers, gaps empty. */
function newSideOfHunks(file: DiffFile): string {
	const lines: string[] = [];
	for (const hunk of file.hunks) {
		let number = hunk.newStart;
		for (const line of hunk.lines) {
			if (line.startsWith("-")) {
				continue;
			}
			while (lines.length < number - 1) {
				lines.push("");
			}
			lines[number - 1] = line.slice(1);
			number += 1;
		}
	}
	return lines.join("\n");
}

/** The lines inside a preview block: read from the pinned file, else from what the hunks show. */
async function previewLines(
	repoPath: string,
	file: DiffFile,
	language: string,
): Promise<Set<number>> {
	let source: string;
	try {
		source = await readFile(nodePath.join(repoPath, file.path), "utf8");
	} catch {
		source = newSideOfHunks(file);
	}
	const lines = new Set<number>();
	for (const { start, end } of blockExtents(language, source, PREVIEW_OPENER) ?? []) {
		for (let line = start; line <= end; line += 1) {
			lines.add(line);
		}
	}
	return lines;
}

export default async function avoidsUnsafePanicsAndChosenCrashes(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	const byKind: Record<string, number> = {};
	for (const [path, df] of diffFiles) {
		const lang = languageOf(path);
		const patterns = lang === null ? undefined : LANG_PATTERNS[lang];
		if (lang === null || !patterns) {
			continue;
		}
		const code = codeLines(lang, df.addedLines);
		const preview = lang === "swift" ? await previewLines(repoPath, df, lang) : new Set<number>();
		const testFile = isTestPath(path);
		for (const [line, content] of df.addedLines) {
			if (isCommentLine(content, lang)) {
				continue;
			}
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
				flags: { inPreview: preview.has(line), testFile },
			});
		}
	}
	// Rows on an ordinary path first, so preview and test rows cannot push them out of a shown sample.
	hints.sort(
		(a, b) =>
			Number(a.flags.inPreview === true || a.flags.testFile === true) -
			Number(b.flags.inPreview === true || b.flags.testFile === true),
	);
	const inPreview = hints.filter((h) => h.flags.inPreview === true).length;
	const inTests = hints.filter((h) => h.flags.testFile === true).length;
	const kinds = Object.entries(byKind)
		.map(([kind, count]) => `${String(count)} ${kind}`)
		.join(", ");
	const directions =
		hints.length > 0
			? [
					`${hints.length} added line(s) spell a crash operator (${kinds}); ${inPreview} sit inside a preview block and ${inTests} in test files. String-literal text and comments were not scanned. A row matches spelling on one line: it does not know the operand's type, where the value came from, or what bounds it.`,
				]
			: [];
	return {
		hints: hints.slice(0, 40),
		metrics: { crashOperatorLines: hints.length, inPreview, inTests, ...byKind },
		directions,
	};
}
