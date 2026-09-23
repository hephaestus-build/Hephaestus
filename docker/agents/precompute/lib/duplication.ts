/**
 * Near-identical runs among the lines a change adds: two blocks whose lines have the same shape
 * once names and literals are set aside, so the reviewer sees every candidate copy with its spans
 * and the names that differ, and judges whether it is a unit of knowledge written twice or an idiom
 * that only looks alike. The pairing is by shape only; what the block means is not read here.
 */

import { isCommentLine } from "./declarations.ts";
import { isTestPath, languageOf } from "./languages.ts";
import type { DiffFile } from "./types.ts";

/** One added source line with its shape: identifiers as `id`, literals as `str`/`num`, the rest kept. */
interface ShapedLine {
	line: number;
	text: string;
	shape: string;
	identifiers: string[];
}

/** Two runs of added lines with the same shape, the names that differ, and how long the run is. */
export interface DuplicatePair {
	a: { path: string; startLine: number; endLine: number };
	b: { path: string; startLine: number; endLine: number };
	lines: number;
	/** The identifiers that differ between the two runs, as `a→b`, at most five. */
	differing: string[];
	/** Whether a line of the run is identical in text, not only in shape. */
	identicalLines: number;
}

/** Fewer lines than this is a guard, an assignment or a modifier chain, not a copied block. */
export const MIN_RUN_LINES = 5;
/** More differing names than this across a run is parallel code, not a copy with names changed. */
export const MAX_DIFFERING_NAMES = 6;

const STRING = /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/gu;
const NUMBER = /\b\d+(?:\.\d+)?\b/gu;
const IDENTIFIER = /\b[A-Za-z_][A-Za-z0-9_]*\b/gu;

function shaped(line: number, text: string, language: string): ShapedLine | null {
	const trimmed = text.trim();
	if (trimmed === "" || isCommentLine(trimmed, language)) {
		return null;
	}
	const identifiers: string[] = [];
	const shape = trimmed
		.replaceAll(STRING, "str")
		.replaceAll(NUMBER, "num")
		.replaceAll(IDENTIFIER, (word) => {
			identifiers.push(word);
			return "id";
		})
		.replaceAll(/\s+/gu, " ");
	return { line, text: trimmed, shape, identifiers };
}

/** A line whose shape is nearly all punctuation — `}`, `)`, `],` — anchors no run on its own. */
function bare(shape: string): boolean {
	return shape.replaceAll(/[^A-Za-z]/gu, "").length === 0;
}

function shapedLines(file: DiffFile): ShapedLine[] {
	const language = languageOf(file.path) ?? "";
	return [...file.addedLines.entries()]
		.toSorted(([a], [b]) => a - b)
		.map(([line, text]) => shaped(line, text, language))
		.filter((line): line is ShapedLine => line !== null);
}

/** Whether two shaped lines are consecutive added lines, so a run is one block and not two hunks. */
function consecutive(lines: ShapedLine[], index: number): boolean {
	const previous = lines[index - 1];
	const current = lines[index];
	return previous !== undefined && current !== undefined && current.line - previous.line <= 2;
}

/**
 * Every pair of runs of at least {@link MIN_RUN_LINES} consecutive added lines with the same
 * shapes, across and within the source files of a change, test files excluded.
 * Overlapping candidates collapse to the longest run starting at each position.
 */
export function duplicatePairs(diffFiles: ReadonlyMap<string, DiffFile>): DuplicatePair[] {
	const files = [...diffFiles.values()]
		.filter((file) => languageOf(file.path) !== null && !isTestPath(file.path))
		.map((file) => ({ path: file.path, lines: shapedLines(file) }));
	// Every run start keyed by the shape of its first MIN_RUN_LINES lines.
	const starts = new Map<string, { file: number; index: number }[]>();
	for (const [fileIndex, file] of files.entries()) {
		for (let index = 0; index + MIN_RUN_LINES <= file.lines.length; index += 1) {
			const window = file.lines.slice(index, index + MIN_RUN_LINES);
			if (window.every((line) => bare(line.shape))) {
				continue;
			}
			if (window.some((_line, offset) => offset > 0 && !consecutive(file.lines, index + offset))) {
				continue;
			}
			const key = window.map((line) => line.shape).join("\n");
			starts.set(key, [...(starts.get(key) ?? []), { file: fileIndex, index }]);
		}
	}
	const pairs: DuplicatePair[] = [];
	for (const candidates of starts.values()) {
		for (let i = 0; i < candidates.length; i += 1) {
			for (let j = i + 1; j < candidates.length; j += 1) {
				const first = candidates[i];
				const second = candidates[j];
				if (first === undefined || second === undefined) {
					continue;
				}
				const pair = extend(files, first, second);
				if (pair === null) {
					continue;
				}
				const inside = pairs.some((seen) => within(pair.a, seen.a) && within(pair.b, seen.b));
				if (!inside) {
					pairs.push(pair);
				}
			}
		}
	}
	return pairs.toSorted((x, y) => y.lines - x.lines || x.a.path.localeCompare(y.a.path));
}

function within(span: DuplicatePair["a"], seen: DuplicatePair["a"]): boolean {
	return (
		span.path === seen.path && span.startLine >= seen.startLine && span.endLine <= seen.endLine
	);
}

/** The longest run of same-shaped lines from two starts, or null when the names differ too much. */
function extend(
	files: { path: string; lines: ShapedLine[] }[],
	first: { file: number; index: number },
	second: { file: number; index: number },
): DuplicatePair | null {
	const fa = files[first.file];
	const fb = files[second.file];
	if (fa === undefined || fb === undefined) {
		return null;
	}
	// The same file: a run must not overlap itself.
	if (first.file === second.file && Math.abs(first.index - second.index) < MIN_RUN_LINES) {
		return null;
	}
	let length = 0;
	let identical = 0;
	const differing = new Map<string, string>();
	for (;;) {
		const la = fa.lines[first.index + length];
		const lb = fb.lines[second.index + length];
		if (la === undefined || lb === undefined || la.shape !== lb.shape) {
			break;
		}
		if (
			length > 0 &&
			(!consecutive(fa.lines, first.index + length) ||
				!consecutive(fb.lines, second.index + length))
		) {
			break;
		}
		if (first.file === second.file && first.index + length >= second.index) {
			break;
		}
		for (const [k, name] of la.identifiers.entries()) {
			const other = lb.identifiers[k];
			if (other !== undefined && other !== name) {
				differing.set(name, other);
			}
		}
		if (la.text === lb.text) {
			identical += 1;
		}
		length += 1;
	}
	if (length < MIN_RUN_LINES || differing.size > MAX_DIFFERING_NAMES) {
		return null;
	}
	const startA = fa.lines[first.index];
	const endA = fa.lines[first.index + length - 1];
	const startB = fb.lines[second.index];
	const endB = fb.lines[second.index + length - 1];
	if (startA === undefined || endA === undefined || startB === undefined || endB === undefined) {
		return null;
	}
	return {
		a: { path: fa.path, startLine: startA.line, endLine: endA.line },
		b: { path: fb.path, startLine: startB.line, endLine: endB.line },
		lines: length,
		differing: [...differing.entries()].slice(0, 5).map(([x, y]) => `${x}→${y}`),
		identicalLines: identical,
	};
}
