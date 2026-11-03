import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm, type FileTree, type SiteRule } from "@hephaestus/practice-scripts/scm";

const SWIFTUI_VIEW = /\b(?:View|App|Scene)\b/u;

const CONCURRENCY: readonly [string, RegExp][] = [
	["detached-task", /\bTask\.detached\b/u],
	["stored-task-handle", /(?:let|var)\s+\w+\s*(?::\s*Task<[^>]*>)?\s*=\s*Task\s*[{(]/u],
	["task-block", /\bTask\s*(?:\(priority:[^)]*\))?\s*\{/u],
	["task-modifier", /\.task\s*(?:\(id:[^)]*\))?\s*\{/u],
	["on-appear", /\.onAppear\s*\{/u],
	["dispatch-queue", /\bDispatchQueue\b/u],
	["main-actor-mark", /@MainActor\b/u],
	["main-actor-run", /\bMainActor\.run\b/u],
	["actor-declaration", /^\s*(?:\w+\s+)*actor\s+\w+/mu],
	["async-function", /\bfunc\s+\w+[^{]*\basync\b/u],
	["await", /\bawait\b/u],
];

const DEFAULT_ISOLATION = /SWIFT_DEFAULT_ACTOR_ISOLATION\s*[:=]|defaultIsolation\s*\(/u;

const PROJECT_FILES = [
	"**/project.yml",
	"**/project.yaml",
	"**/*.xcodeproj/project.pbxproj",
	"**/Package.swift",
];

// Actor-isolation settings belong to one target and configuration; partial project text cannot establish a repository-wide default.
async function defaultIsolationLines(
	tree: FileTree | null,
): Promise<{ file: string; line: number }[]> {
	if (tree === null) {
		return [];
	}
	const files: string[] = [];
	try {
		for (const glob of PROJECT_FILES) {
			const { paths } = await tree.list(glob);
			files.push(...paths);
		}
	} catch {
		return [];
	}
	const found: { file: string; line: number }[] = [];
	const projectFiles = files.filter(
		(f) => !/(?:^|\/)(?:Pods|\.build|DerivedData|Carthage)\//u.test(f),
	);
	for (const file of projectFiles.slice(0, 20)) {
		const source = await tree.read(file).catch(() => null);
		if (source?.status !== "FOUND") {
			continue;
		}
		for (const [index, line] of source.text.split(/\r?\n/u).entries()) {
			if (DEFAULT_ISOLATION.test(line)) {
				found.push({ file, line: index + 1 });
			}
		}
	}
	return found.slice(0, 10);
}

export const LABELS = {
	"detached-task": "A selected Task.detached call.",
	"stored-task-handle": "A selected property that stores a Task.",
	"task-block": "A selected unstructured Task { } block.",
	"task-modifier": "A selected .task view modifier.",
	"on-appear": "A selected .onAppear block.",
	"dispatch-queue": "A selected line that uses DispatchQueue.",
	"main-actor-mark": "A selected @MainActor attribute.",
	"main-actor-run": "A selected MainActor.run call.",
	"actor-declaration": "A selected actor declaration.",
	"async-function": "A selected async function declaration.",
	await: "A selected await expression.",
	"default-isolation-setting":
		"A line of a project file that names a default actor isolation for one target or module.",
};

const LABEL_OF: Record<string, string> = LABELS;

const rulesOf = (rows: readonly [string, RegExp][]): SiteRule[] =>
	rows.map(([kind, test]) => ({ kind, label: LABEL_OF[kind] ?? "", test }));

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const found = await work.sites(
			{ swift: rulesOf(CONCURRENCY) },
			{ overlap: "first", enclosing: { scope: SWIFTUI_VIEW }, listed: 60 },
		);
		const leads = [...found.leads];
		const count = (kind: string) => found.counts.get(kind) ?? 0;
		const unstructuredInViews = found.listed.filter(
			(site) => site.kind === "task-block" && site.facts.inScope === true,
		).length;
		const facts = {
			concurrencyLinesSelected: found.matched,
			taskBlocks: count("task-block"),
			taskBlocksInListedViewRows: unstructuredInViews,
			taskModifiers: count("task-modifier"),
			detachedTasks: count("detached-task"),
			dispatchQueues: count("dispatch-queue"),
			mainActorMarks: count("main-actor-mark") + count("main-actor-run"),
			filesWithUnknownLocation: found.filesWithUnknownLocation,
			filesScanned: found.filesScanned,
			linesSelected: found.linesSelected,
		};
		const directions: string[] = [...found.listedNote];
		if (leads.length === 0) {
			directions.push(...found.noMatch);
		}
		if (unstructuredInViews > 0) {
			directions.push(
				`${unstructuredInViews} Task { } block(s) selected inside view types — read the lifetime each needs: work tied to the view belongs to a lifecycle that cancels it, while a Task started from a user action may legitimately finish after the view is gone.`,
			);
		}
		if (facts.detachedTasks + facts.dispatchQueues > 0) {
			directions.push(
				`${facts.detachedTasks} detached task(s) and ${facts.dispatchQueues} dispatch queue use(s) — read whether the code shows a need for independent execution or scheduling; no written reason is required when it does.`,
			);
		}
		if (found.matched > 0) {
			const settings = await defaultIsolationLines(work.head);
			leads.push(
				...settings.map((at): ReviewLead => ({ at, label: LABELS["default-isolation-setting"] })),
			);
			directions.push(
				settings.length > 0
					? "Each default-isolation-setting lead applies only to its own target or SwiftPM module and build configuration, and a commented line sets nothing: read which target and configuration build the changed file before relying on any of them."
					: "No default actor isolation text was found in the project files read, which leaves the module's default unknown rather than nonisolated.",
				"A type's isolation comes from its declaration, its module's setting or what it inherits. A missing @MainActor on a selected line does not by itself put a mutation off the main actor.",
				"Check the SDK before relying on inherited SwiftUI View isolation. Swallowed failures belong to handles-errors-instead-of-swallowing-them, not this practice.",
			);
		}
		return ctx.review({ leads, facts, directions });
	},
});
