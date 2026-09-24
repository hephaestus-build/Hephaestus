// Precompute HINTS for owns-state-at-the-right-level: every state declaration and wiring ADDED in Swift
// files, placed in its enclosing type. The rules (local values @State, shared models observed once,
// edits through Binding, derived values computed) are the review's to apply; the script enumerates
// the declarations so none is missed and counts a model created inside a view type as a lead.
import { scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

const SWIFTUI_VIEW = /\b(?:View|App|Scene)\b/u;

const STATE_DECLARATIONS: readonly SourcePattern[] = [
	["@State creating an object", /@State(?:Object)?\b[^=]*=\s*[A-Z][A-Za-z0-9_]*\s*\(/u],
	["@State", /@State\b/u],
	["@StateObject", /@StateObject\b/u],
	["@ObservedObject", /@ObservedObject\b/u],
	["@Binding", /@Binding\b/u],
	["@Bindable", /@Bindable\b/u],
	["@Environment", /@Environment(?:Object)?\b/u],
	["@AppStorage", /@AppStorage\b/u],
	["@Query", /@Query\b/u],
	["@Observable type", /@Observable\b/u],
	["ObservableObject type", /\bObservableObject\b/u],
	["@Published", /@Published\b/u],
	[
		"mutable stored property",
		/^\s*(?:private\s+|fileprivate\s+|internal\s+)?var\s+[A-Za-z_][A-Za-z0-9_]*\s*(?::\s*[^{=\n]+)?(?:=\s*[^{\n]+)?\s*$/u,
	],
	["environment injection", /\.environment(?:Object)?\s*\(/u],
];

export default async function ownsStateAtTheRightLevel(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const scan = await scanAddedLines(repoPath, diffFiles, {
		languages: ["swift"],
		patterns: STATE_DECLARATIONS,
		scope: SWIFTUI_VIEW,
		maxHints: 60,
	});
	const inViews = scan.hints.filter((h) => h.flags.inScope === true);
	const objectsCreatedInViews = inViews.filter(
		(h) => h.pattern === "@State creating an object" || h.pattern === "@StateObject",
	).length;
	const storedVarsInViews = inViews.filter((h) => h.pattern === "mutable stored property").length;
	const directions: string[] = [];
	if (scan.hints.length > 0) {
		directions.push(
			`${scan.hints.length} state declaration(s) or wiring line(s) added; ${inViews.length} inside view types. Walk each against the four rules.`,
		);
	}
	if (objectsCreatedInViews > 0) {
		directions.push(
			`${objectsCreatedInViews} object(s) created in a view's @State/@StateObject — check in the checkout whether a parent already owns an instance of the same type.`,
		);
	}
	if (storedVarsInViews > 0) {
		directions.push(
			`${storedVarsInViews} plain stored var(s) in a view type — a view mutating one of these without @State is a rule-1 lapse; a let-like var the view never mutates is not.`,
		);
	}
	return {
		hints: scan.hints,
		metrics: {
			stateDeclarationsAdded: scan.hints.length,
			stateDeclarationsInViews: inViews.length,
			objectsCreatedInViews,
			storedVarsInViews,
			filesWithoutCheckout: scan.filesWithoutCheckout,
			filesScanned: scan.filesScanned,
			linesAdded: scan.linesAdded,
		},
		directions,
	};
}
