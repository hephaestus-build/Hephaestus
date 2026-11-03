import { definePracticeScript } from "@hephaestus/practice-scripts";
import { scm, type SiteRule } from "@hephaestus/practice-scripts/scm";

const SWIFTUI_VIEW = /\b(?:View|App|Scene)\b/u;

const IO_IN_VIEW: readonly [string, RegExp][] = [
	["urlsession-request", /\bURLSession\b|\bURLRequest\s*\(/u],
	["json-coding", /\bJSON(?:Decoder|Encoder)\s*\(/u],
	["core-data-fetch", /\bNSFetchRequest\b|\.fetch\s*\(\s*NSFetchRequest/u],
	["user-defaults-access", /\bUserDefaults\b/u],
	["file-manager-access", /\bFileManager\b/u],
	["data-contents-of", /\bData\(contentsOf:/u],
];

export const LABELS = {
	"urlsession-request": "A selected line that uses URLSession or makes a URLRequest.",
	"json-coding": "A selected line that makes a JSONDecoder or a JSONEncoder.",
	"core-data-fetch": "A selected line that makes or runs an NSFetchRequest.",
	"user-defaults-access": "A selected line that uses UserDefaults.",
	"file-manager-access": "A selected line that uses FileManager.",
	"data-contents-of": "A selected line that reads a file with Data(contentsOf:).",
};

const LABEL_OF: Record<string, string> = LABELS;

const rulesOf = (rows: readonly [string, RegExp][]): SiteRule[] =>
	rows.map(([kind, test]) => ({ kind, label: LABEL_OF[kind] ?? "", test }));

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const found = await work.sites(
			{ swift: rulesOf(IO_IN_VIEW) },
			{ overlap: "first", enclosing: { scope: SWIFTUI_VIEW, only: true } },
		);
		const leads = [...found.leads];
		const directions: string[] = [...found.listedNote];
		if (found.matched > 0) {
			directions.push(
				`${String(found.matched)} candidate I/O calls were selected inside or possibly inside view types. Read the enclosing type of each lead. A line in a store or model is not the view's I/O.`,
				"SwiftUI @AppStorage and @SceneStorage, Core Data @FetchRequest, and the supported SwiftData view-side APIs are not direct I/O.",
			);
		}
		if (found.filesWithUnknownLocation > 0) {
			directions.push(
				`${String(found.filesWithUnknownLocation)} Swift file(s) have declarations the script could not place; their leads carry enclosing=unknown. Inspect their full source to locate them.`,
			);
		}
		if (leads.length === 0) {
			directions.push(...found.noMatch);
		}
		return ctx.review({
			leads,
			facts: {
				ioCallsInViews: found.matched,
				viewLinesSelected: found.linesInScope,
				swiftLinesSelected: found.linesSelected,
				filesWithUnknownLocation: found.filesWithUnknownLocation,
				filesScanned: found.filesScanned,
				linesSelected: found.linesSelected,
			},
			directions,
		});
	},
});
