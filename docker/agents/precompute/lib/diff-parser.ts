import type { DiffFile, DiffHunk } from "./types.ts";

/**
 * Parse a unified diff (with optional [L<n>] annotations) into structured DiffFile objects.
 * Returns a Map of file path -> DiffFile.
 */
export function parseDiff(diffContent: string): Map<string, DiffFile> {
	const files = new Map<string, DiffFile>();

	const fileDiffs = diffContent.split(/^diff --git /m).filter(Boolean);

	for (const fileDiff of fileDiffs) {
		const lines = fileDiff.split("\n");

		// Extract file path from "a/path b/path"
		const filePath = lines[0]?.match(/a\/(.+?)\s+b\/(.+)/)?.[2];
		if (filePath === undefined) continue;

		const addedLines = new Map<number, string>();
		const removedLines = new Map<number, string>();
		const hunks: DiffHunk[] = [];

		let currentHunk: DiffHunk | null = null;
		let newLineNum = 0;
		let oldLineNum = 0;

		for (const line of lines) {
			// Hunk header: @@ -oldStart,oldCount +newStart,newCount @@
			// Both counts are omitted from the header when they are 1, so they default; both start line
			// numbers are required for the header to be a hunk header at all.
			const [, oldStart, oldCount = "1", newStart, newCount = "1"] =
				line.match(/^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@/) ?? [];
			if (oldStart !== undefined && newStart !== undefined) {
				currentHunk = {
					oldStart: Number.parseInt(oldStart, 10),
					oldCount: Number.parseInt(oldCount, 10),
					newStart: Number.parseInt(newStart, 10),
					newCount: Number.parseInt(newCount, 10),
					lines: [],
				};
				hunks.push(currentHunk);
				oldLineNum = currentHunk.oldStart;
				newLineNum = currentHunk.newStart;
				continue;
			}

			if (!currentHunk) continue;

			// Strip [L<n>] annotation if present
			const stripped = line.replace(/^\[L\d+\]\s*/, "");

			if (stripped.startsWith("+") && !stripped.startsWith("+++")) {
				addedLines.set(newLineNum, stripped.slice(1));
				currentHunk.lines.push(stripped);
				newLineNum++;
			} else if (stripped.startsWith("-") && !stripped.startsWith("---")) {
				removedLines.set(oldLineNum, stripped.slice(1));
				currentHunk.lines.push(stripped);
				oldLineNum++;
			} else if (!stripped.startsWith("\\")) {
				currentHunk.lines.push(stripped);
				newLineNum++;
				oldLineNum++;
			}
		}

		const normalizedPath = filePath.replace(/^\.\//, "");
		files.set(normalizedPath, { path: normalizedPath, addedLines, removedLines, hunks });
	}

	return files;
}

/** Check if a given file path + line number is in the diff (on a + line) */
export function isInDiff(
	diffFiles: Map<string, DiffFile>,
	filePath: string,
	lineNum: number,
): boolean {
	const df =
		diffFiles.get(filePath) ??
		[...diffFiles.values()].find((f) => filePath.endsWith(f.path) || f.path.endsWith(filePath));
	if (!df) return false;
	return df.addedLines.has(lineNum);
}
