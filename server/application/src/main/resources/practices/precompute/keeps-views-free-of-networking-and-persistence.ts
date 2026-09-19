// Precompute HINTS for keeps-views-free-of-networking-and-persistence: locate I/O calls ADDED inside
// SwiftUI view types — requests, decoding, files, preferences, Core Data. SwiftData's view-side idiom
// (`@Query`, `modelContext.insert`) is the framework's own and is not listed. Each is a candidate the review places;
// a `load()` call on a store is not I/O in the view, and the script says which type a line lies in so
// the review does not have to find the declaration itself.
import { scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

/** A SwiftUI view: a type conforming to `View`, or an `App`/`Scene`, which also declare a body. */
const SWIFTUI_VIEW = /\b(?:View|App|Scene)\b/;

const IO_IN_VIEW: readonly SourcePattern[] = [
	["URLSession request", /\bURLSession\b|\bURLRequest\s*\(/],
	["JSON coding", /\bJSON(?:Decoder|Encoder)\s*\(/],
	["Core Data fetch", /\bNSFetchRequest\b|\.fetch\s*\(\s*NSFetchRequest/],
	["UserDefaults access", /\bUserDefaults\b/],
	["FileManager access", /\bFileManager\b/],
	["Data(contentsOf:)", /\bData\(contentsOf:/],
];

export default async function keepsViewsFreeOfNetworkingAndPersistence(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const scan = await scanAddedLines(repoPath, diffFiles, {
		languages: ["swift"],
		patterns: IO_IN_VIEW,
		scope: SWIFTUI_VIEW,
		onlyInScope: true,
	});
	const directions: string[] = [];
	if (scan.hints.length > 0) {
		directions.push(
			`${scan.hints.length} I/O call(s) added inside or possibly inside a view type — read each hint's enclosing type; a line placed in a store or model is not the view's I/O.`,
		);
	}
	if (scan.filesWithoutCheckout > 0) {
		directions.push(
			`${scan.filesWithoutCheckout} Swift file(s) could not be read from the checkout; their hints carry enclosing=unknown and need the diff context to place.`,
		);
	}
	return {
		hints: scan.hints,
		metrics: {
			ioCallsInViews: scan.hints.length,
			viewLinesAdded: scan.linesInScope,
			swiftLinesAdded: scan.linesAdded,
			filesWithoutCheckout: scan.filesWithoutCheckout,
		},
		directions,
	};
}
