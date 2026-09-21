// Precompute HINTS for uses-structured-concurrency-safely: every concurrency construct ADDED in Swift
// files, placed in its enclosing type. A `Task {` inside a view, a detached task, a dispatch queue and
// an `.onAppear` are leads; whether the work must outlive the view, and whether a mutation lands on the
// main actor, is the review's to decide from the checkout.
import { countLabel, scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

const SWIFTUI_VIEW = /\b(?:View|App|Scene)\b/u;

const CONCURRENCY: readonly SourcePattern[] = [
	["Task.detached", /\bTask\.detached\b/u],
	["stored task handle", /(?:let|var)\s+\w+\s*(?::\s*Task<[^>]*>)?\s*=\s*Task\s*[{(]/u],
	["Task { }", /\bTask\s*(?:\(priority:[^)]*\))?\s*\{/u],
	[".task modifier", /\.task\s*(?:\(id:[^)]*\))?\s*\{/u],
	[".onAppear", /\.onAppear\s*\{/u],
	["DispatchQueue", /\bDispatchQueue\b/u],
	["@MainActor", /@MainActor\b/u],
	["MainActor.run", /\bMainActor\.run\b/u],
	["actor declaration", /^\s*(?:\w+\s+)*actor\s+\w+/u],
	["async function", /\bfunc\s+\w+[^{]*\basync\b/u],
	["await", /\bawait\b/u],
];

export default async function usesStructuredConcurrencySafely(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const scan = await scanAddedLines(repoPath, diffFiles, {
		languages: ["swift"],
		patterns: CONCURRENCY,
		scope: SWIFTUI_VIEW,
		maxHints: 60,
	});
	const count = (label: string) => countLabel(scan, label);
	const unstructuredInViews = scan.hints.filter(
		(h) => h.pattern === "Task { }" && h.flags.inScope === true,
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
		filesScanned: scan.filesScanned,
		linesAdded: scan.linesAdded,
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
