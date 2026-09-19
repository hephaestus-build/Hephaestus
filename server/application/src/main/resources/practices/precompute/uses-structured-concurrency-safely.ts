// Precompute HINTS for uses-structured-concurrency-safely: every concurrency construct ADDED in Swift
// files, placed in its enclosing type. A `Task {` inside a view, a detached task, a dispatch queue and
// an `.onAppear` are leads; whether the work must outlive the view, and whether a mutation lands on the
// main actor, is the review's to decide from the checkout.
import { scanSwift, type SwiftPattern } from "../lib/swift-scan.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

const CONCURRENCY: readonly SwiftPattern[] = [
	["Task.detached", /\bTask\.detached\b/],
	["stored task handle", /(?:let|var)\s+\w+\s*(?::\s*Task<[^>]*>)?\s*=\s*Task\s*[{(]/],
	["Task { }", /\bTask\s*(?:\(priority:[^)]*\))?\s*\{/],
	[".task modifier", /\.task\s*(?:\(id:[^)]*\))?\s*\{/],
	[".onAppear", /\.onAppear\s*\{/],
	["DispatchQueue", /\bDispatchQueue\b/],
	["@MainActor", /@MainActor\b/],
	["MainActor.run", /\bMainActor\.run\b/],
	["actor declaration", /^\s*(?:\w+\s+)*actor\s+\w+/],
	["async function", /\bfunc\s+\w+[^{]*\basync\b/],
	["await", /\bawait\b/],
];

export default async function usesStructuredConcurrencySafely(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const scan = await scanSwift(repoPath, diffFiles, CONCURRENCY, { maxHints: 60 });
	const count = (label: string) => scan.hints.filter((h) => h.pattern === label).length;
	const unstructuredInViews = scan.hints.filter(
		(h) => h.pattern === "Task { }" && h.flags.inViewType === true,
	).length;
	const metrics = {
		concurrencyLinesAdded: scan.hints.length,
		taskBlocks: count("Task { }"),
		taskBlocksInViews: unstructuredInViews,
		taskModifiers: count(".task modifier"),
		detachedTasks: count("Task.detached"),
		dispatchQueues: count("DispatchQueue"),
		mainActorMarks: count("@MainActor") + count("MainActor.run"),
		filesWithoutCheckout: scan.filesWithoutCheckout,
	};
	const directions: string[] = [];
	if (unstructuredInViews > 0) {
		directions.push(
			`${unstructuredInViews} Task { } block(s) added inside view types — read whether each sits in .onAppear or an action, whether a handle is stored, and whether .task was available.`,
		);
	}
	if (metrics.detachedTasks + metrics.dispatchQueues > 0) {
		directions.push(
			`${metrics.detachedTasks} detached task(s) and ${metrics.dispatchQueues} dispatch queue use(s) — look for the stated reason next to each.`,
		);
	}
	if (scan.hints.length > 0 && metrics.mainActorMarks === 0) {
		directions.push(
			"No @MainActor mark arrived with the added concurrency; check the isolation of each type whose UI state an await mutates.",
		);
	}
	return { hints: scan.hints, metrics, directions };
}
