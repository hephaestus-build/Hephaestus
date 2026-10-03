// Precompute HINTS for uses-structured-concurrency-safely: every concurrency construct ADDED in Swift
// files, placed in its enclosing type, and the lines of the project files that mention a default actor
// isolation. Whether the work must outlive the view, and whether a mutation lands on the main actor, is
// the review's to decide from the checkout.
import { readFile } from "node:fs/promises";
import path from "node:path";

import { globFilesSync } from "../lib/files.ts";
import { countLabel, sampleNote, scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
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

/** Text naming a default actor isolation: an Xcode build setting or a SwiftPM target setting. */
const DEFAULT_ISOLATION = /SWIFT_DEFAULT_ACTOR_ISOLATION\s*[:=]|defaultIsolation\s*\(/u;

/**
 * Lines of the first few project files that mention a default actor isolation, as `path:line text`. The
 * setting belongs to one target or SwiftPM module and build configuration, and a line may be commented
 * out, so a line found here is a place to read, not the isolation of any changed type.
 */
async function defaultIsolationLines(repoPath: string): Promise<string[]> {
	let files: string[];
	try {
		files = [
			...globFilesSync("**/project.yml", repoPath),
			...globFilesSync("**/project.yaml", repoPath),
			...globFilesSync("**/*.xcodeproj/project.pbxproj", repoPath),
			...globFilesSync("**/Package.swift", repoPath),
		].filter((f) => !/(?:^|\/)(?:Pods|\.build|DerivedData|Carthage)\//u.test(f));
	} catch {
		return [];
	}
	const found: string[] = [];
	for (const file of files.slice(0, 20)) {
		let text: string;
		try {
			text = await readFile(path.join(repoPath, file), "utf8");
		} catch {
			continue;
		}
		for (const [index, line] of text.split(/\r?\n/u).entries()) {
			if (DEFAULT_ISOLATION.test(line)) {
				found.push(`${file}:${String(index + 1)} ${line.trim().slice(0, 120)}`);
			}
		}
	}
	return found.slice(0, 10);
}

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
		concurrencyLinesAdded: scan.matched,
		taskBlocks: count("Task { }"),
		taskBlocksInListedViewRows: unstructuredInViews,
		taskModifiers: count(".task modifier"),
		detachedTasks: count("Task.detached"),
		dispatchQueues: count("DispatchQueue"),
		mainActorMarks: count("@MainActor") + count("MainActor.run"),
		filesWithoutCheckout: scan.filesWithoutCheckout,
		filesScanned: scan.filesScanned,
		linesAdded: scan.linesAdded,
	};
	const directions: string[] = [...sampleNote(scan)];
	if (unstructuredInViews > 0) {
		directions.push(
			`${unstructuredInViews} Task { } block(s) added inside view types — read the lifetime each needs: work tied to the view belongs to a lifecycle that cancels it, while a Task started from a user action may legitimately finish after the view is gone.`,
		);
	}
	if (metrics.detachedTasks + metrics.dispatchQueues > 0) {
		directions.push(
			`${metrics.detachedTasks} detached task(s) and ${metrics.dispatchQueues} dispatch queue use(s) — read whether the code shows a need for independent execution or scheduling; no written reason is required when it does.`,
		);
	}
	if (scan.matched > 0) {
		const settings = await defaultIsolationLines(repoPath);
		directions.push(
			settings.length > 0
				? `Default actor isolation text in project files: ${settings.join("; ")}. Each applies only to its own target or SwiftPM module and build configuration, and a commented line sets nothing: read which target and configuration build the changed file before relying on any of them.`
				: "No default actor isolation text was found in the project files read, which leaves the module's default unknown rather than nonisolated.",
			"A type's isolation comes from its declaration, its module's setting or what it inherits; a missing @MainActor on an added line does not by itself put a mutation off the main actor.",
		);
	}
	return { hints: scan.hints, metrics, directions };
}
