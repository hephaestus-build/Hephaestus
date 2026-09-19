// Precompute HINTS for keeps-views-free-of-networking-and-persistence: locate I/O calls ADDED inside
// SwiftUI view types — requests, decoding, files, preferences, Core Data. SwiftData's view-side idiom
// (`@Query`, `modelContext.insert`) is the framework's own and is not listed. Each is a candidate the review places;
// a `load()` call on a store is not I/O in the view, and the script says which type a line lies in so
// the review does not have to find the declaration itself.
import { scanSwift, type SwiftPattern } from "../lib/swift-scan.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

const IO_IN_VIEW: readonly SwiftPattern[] = [
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
	const scan = await scanSwift(repoPath, diffFiles, IO_IN_VIEW, { onlyInViews: true });
	const directions: string[] = [];
	if (scan.hints.length > 0) {
		directions.push(
			`${scan.hints.length} I/O call(s) added inside or possibly inside a view type — read each hint's enclosingType; a line placed in a store or model is not the view's I/O.`,
		);
	}
	if (scan.filesWithoutCheckout > 0) {
		directions.push(
			`${scan.filesWithoutCheckout} Swift file(s) could not be read from the checkout; their hints carry enclosingType=unknown and need the diff context to place.`,
		);
	}
	return {
		hints: scan.hints,
		metrics: {
			ioCallsInViews: scan.hints.length,
			viewLinesAdded: scan.viewLinesAdded,
			swiftLinesAdded: scan.swiftLinesAdded,
			filesWithoutCheckout: scan.filesWithoutCheckout,
		},
		directions,
	};
}
