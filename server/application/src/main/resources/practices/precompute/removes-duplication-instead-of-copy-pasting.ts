// Precompute FACTS for removes-duplication-instead-of-copy-pasting: every pair of added blocks
// whose lines have the same shape once names and literals are set aside — the two spans, the run
// length, how many lines are identical to the character, and the names that differ. The review
// judges each pair: a unit of knowledge written twice, or an idiom the platform makes look alike.
import { duplicatePairs, MIN_RUN_LINES } from "../lib/duplication.ts";
import { isTestPath, languageOf } from "../lib/languages.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

const MAX_PAIRS = 20;

export default function removesDuplicationInsteadOfCopyPasting(
	_repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const sourceFiles = [...diffFiles.values()].filter(
		(file) => languageOf(file.path) !== null && !isTestPath(file.path),
	);
	const linesAdded = sourceFiles.reduce((sum, file) => sum + file.addedLines.size, 0);
	const pairs = duplicatePairs(diffFiles);
	const hints: Hint[] = pairs.slice(0, MAX_PAIRS).map((pair) => ({
		file: pair.a.path,
		line: pair.a.startLine,
		pattern: "same-shaped added blocks",
		context: `[L${String(pair.a.startLine)}]-[L${String(pair.a.endLine)}] and ${pair.b.path} [L${String(pair.b.startLine)}]-[L${String(pair.b.endLine)}]: ${String(pair.lines)} lines of one shape, ${String(pair.identicalLines)} identical`,
		inDiff: true,
		flags: {
			otherPath: pair.b.path,
			otherStart: pair.b.startLine,
			otherEnd: pair.b.endLine,
			lines: pair.lines,
			identicalLines: pair.identicalLines,
			differing: pair.differing.join(", "),
		},
	}));
	const directions =
		pairs.length === 0
			? [
					`No two runs of ${String(MIN_RUN_LINES)} or more added lines share a shape once names and literals are set aside. A copy this pairing cannot see — the same computation spelled with different statements, or shorter than ${String(MIN_RUN_LINES)} lines — is still yours to read for.`,
				]
			: [
					`${String(pairs.length)} pair(s) of added blocks with the same line shapes, one row each with both spans and the names that differ. A pair is a copy when the run is one unit of knowledge — a computation, a mapping, a view body — repeated with names changed; it is not when the platform dictates the shape (a modifier chain, a switch arm, a registration) or the blocks are test fixtures. Quote both spans.`,
				];
	return {
		hints,
		metrics: { linesAdded, filesScanned: sourceFiles.length, pairs: pairs.length },
		directions,
	};
}
