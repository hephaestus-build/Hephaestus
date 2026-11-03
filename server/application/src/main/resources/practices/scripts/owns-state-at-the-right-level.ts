import { definePracticeScript } from "@hephaestus/practice-scripts";
import { scm, type SiteRule } from "@hephaestus/practice-scripts/scm";

const SWIFTUI_VIEW = /\b(?:View|App|Scene)\b/u;

const STATE_DECLARATIONS: readonly [string, RegExp][] = [
	["state-creating-an-object", /@State(?:Object)?\b[^=]*=\s*[A-Z][A-Za-z0-9_]*\s*\(/u],
	["state-property", /@State\b/u],
	["state-object", /@StateObject\b/u],
	["observed-object", /@ObservedObject\b/u],
	["binding", /@Binding\b/u],
	["bindable", /@Bindable\b/u],
	["environment-value", /@Environment(?:Object)?\b/u],
	["app-storage", /@AppStorage\b/u],
	["query", /@Query\b/u],
	["observable-type", /@Observable\b/u],
	["observable-object-type", /\bObservableObject\b/u],
	["published-property", /@Published\b/u],
	[
		"mutable-stored-property",
		/^\s*(?:private\s+|fileprivate\s+|internal\s+)?var\s+[A-Za-z_][A-Za-z0-9_]*\s*(?::\s*[^{=\n]+)?(?:=\s*[^{\n]+)?\s*$/mu,
	],
	["environment-injection", /\.environment(?:Object)?\s*\(/u],
];

export const LABELS = {
	"state-creating-an-object":
		"A selected @State or @StateObject declaration that creates an object.",
	"state-property": "A selected @State declaration.",
	"state-object": "A selected @StateObject declaration.",
	"observed-object": "A selected @ObservedObject declaration.",
	binding: "A selected @Binding declaration.",
	bindable: "A selected @Bindable declaration.",
	"environment-value": "A selected @Environment or @EnvironmentObject declaration.",
	"app-storage": "A selected @AppStorage declaration.",
	query: "A selected @Query declaration.",
	"observable-type": "A selected line with @Observable, usually on a model type.",
	"observable-object-type": "A selected line that names ObservableObject, usually on a model type.",
	"published-property": "A selected @Published property.",
	"mutable-stored-property": "A selected stored var without a property wrapper.",
	"environment-injection": "A selected .environment or .environmentObject modifier.",
};

const LABEL_OF: Record<string, string> = LABELS;

const rulesOf = (rows: readonly [string, RegExp][]): SiteRule[] =>
	rows.map(([kind, test]) => ({ kind, label: LABEL_OF[kind] ?? "", test }));

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const found = await work.sites(
			{ swift: rulesOf(STATE_DECLARATIONS) },
			{ overlap: "first", enclosing: { scope: SWIFTUI_VIEW }, listed: 60 },
		);
		const leads = [...found.leads];
		const inViews = found.listed.filter((site) => site.facts.inScope === true);
		const objectsCreatedInViews = inViews.filter(
			(site) => site.kind === "state-creating-an-object" || site.kind === "state-object",
		).length;
		const storedVarsInViews = inViews.filter(
			(site) => site.kind === "mutable-stored-property",
		).length;
		const directions: string[] = [...found.listedNote];
		if (leads.length === 0) {
			directions.push(...found.noMatch);
		}
		if (found.matched > 0) {
			directions.push(
				`${found.matched} state declaration(s) or wiring line(s) selected; ${inViews.length} of the listed rows lie inside view types. Walk each against the four rules.`,
			);
		}
		if (objectsCreatedInViews > 0) {
			directions.push(
				`${objectsCreatedInViews} object(s) created in a view's @State/@StateObject — check in the checkout whether a parent already owns an instance of the same type.`,
			);
		}
		if (storedVarsInViews > 0) {
			directions.push(
				`${storedVarsInViews} plain stored var(s) in a view type. Read whether the view mutates each one: the first rule covers a value that the view owns and mutates. A var that the view only receives from its parent is an input.`,
			);
		}
		return ctx.review({
			leads,
			facts: {
				stateDeclarationsSelected: found.matched,
				stateDeclarationsInViews: inViews.length,
				objectsCreatedInViews,
				storedVarsInViews,
				filesWithUnknownLocation: found.filesWithUnknownLocation,
				filesScanned: found.filesScanned,
				linesSelected: found.linesSelected,
			},
			directions,
		});
	},
});
