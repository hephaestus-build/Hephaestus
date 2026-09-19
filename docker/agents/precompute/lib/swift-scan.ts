/**
 * The walk the Swift practices share: every added line of every Swift file, placed in the type that
 * encloses it, matched against a script's closed list of patterns. The script owns the list and the
 * directions; this owns the placing, the comment skip and the hint shape.
 */

import {
	enclosingType,
	isSwiftComment,
	type SwiftTypeRange,
	swiftTypeRangesOf,
} from "./swift-views.ts";
import type { DiffFile, Hint } from "./types.ts";

export type SwiftPattern = [label: string, test: RegExp];

export interface SwiftScan {
	hints: Hint[];
	/** Added lines inside a view type, across the change. */
	viewLinesAdded: number;
	/** Added lines in any Swift file. */
	swiftLinesAdded: number;
	/** Files whose enclosing types could not be read from the checkout. */
	filesWithoutCheckout: number;
}

export function isSwiftSource(path: string): boolean {
	return path.endsWith(".swift") && !/(?:^|\/)(?:Tests?|UITests?)\//.test(path);
}

/** Flags every hint carries: the enclosing type and whether it is a view, or "unknown" with no checkout. */
function placement(
	range: SwiftTypeRange | undefined,
	known: boolean,
): Record<string, string | boolean> {
	if (!known) return { enclosingType: "unknown", inViewType: "unknown" };
	return {
		enclosingType: range ? `${range.kind} ${range.name}` : "file scope",
		inViewType: range?.isView ?? false,
	};
}

export async function scanSwift(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	patterns: readonly SwiftPattern[],
	options: { onlyInViews?: boolean; maxHints?: number } = {},
): Promise<SwiftScan> {
	const scan: SwiftScan = {
		hints: [],
		viewLinesAdded: 0,
		swiftLinesAdded: 0,
		filesWithoutCheckout: 0,
	};
	for (const [path, df] of diffFiles) {
		if (!isSwiftSource(path)) continue;
		const ranges = await swiftTypeRangesOf(repoPath, path);
		if (ranges === null) scan.filesWithoutCheckout++;
		for (const [line, content] of df.addedLines) {
			if (isSwiftComment(content)) continue;
			scan.swiftLinesAdded++;
			const range = ranges ? enclosingType(ranges, line) : undefined;
			const inView = ranges === null ? undefined : (range?.isView ?? false);
			if (inView) scan.viewLinesAdded++;
			if (options.onlyInViews && inView === false) continue;
			for (const [label, re] of patterns) {
				if (!re.test(content)) continue;
				scan.hints.push({
					file: path,
					line,
					pattern: label,
					context: content.trim().slice(0, 160),
					inDiff: true,
					flags: placement(range, ranges !== null),
				});
				break;
			}
		}
	}
	scan.hints = scan.hints.slice(0, options.maxHints ?? 40);
	return scan;
}
