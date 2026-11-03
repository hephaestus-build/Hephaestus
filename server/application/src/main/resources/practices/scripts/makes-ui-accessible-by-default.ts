import { definePracticeScript } from "@hephaestus/practice-scripts";
import { scm, type SiteRule } from "@hephaestus/practice-scripts/scm";

// SwiftUI custom(size:) scales; custom(fixedSize:) does not.
const INTERFACE_LINES: readonly [string, RegExp][] = [
	[
		"accessibility-modifier",
		/\.accessibility(?:Label|Hint|Hidden|AddTraits|Value|Identifier)\s*\(/u,
	],
	["label-with-text", /\bLabel\s*\(\s*"/u],
	["symbol-image", /\bImage\s*\(\s*systemName:/u],
	["asset-image", /\bImage\s*\(\s*(?:"|decorative:|[a-z]\w*\.)/u],
	["fixed-size-custom-font", /\.custom\s*\([^)]*\bfixedSize:/u],
	["system-font-with-a-point-size", /\.system\s*\(\s*size:/u],
	["scaled-custom-font", /\.custom\s*\([^)]*\bsize:/u],
	[
		"semantic-font",
		/\.font\s*\(\s*\.(?:largeTitle|title[23]?|headline|subheadline|body|callout|footnote|caption2?)\b/u,
	],
	["height-cap", /\.frame\s*\([^)]*\bheight:\s*\d/u],
	["line-limit", /\.lineLimit\s*\(\s*\d/u],
	[
		"colour-by-state",
		/\.foregroundStyle\s*\(\s*[^)]*\?\s*\.(?:red|green|orange|yellow)|\.foregroundColor\s*\(\s*[^)]*\?\s*\.(?:red|green|orange|yellow)/u,
	],
	["button", /\bButton\s*[({]/u],
	["toggle", /\bToggle\s*\(/u],
];

export const LABELS = {
	"accessibility-modifier":
		"A selected accessibility modifier, such as accessibilityLabel or accessibilityHidden.",
	"label-with-text": "A selected Label with a text title.",
	"symbol-image": "A selected Image(systemName:).",
	"asset-image": "A selected Image from an asset or a decorative image.",
	"fixed-size-custom-font":
		"A selected custom font with fixedSize:, which does not scale with the text size.",
	"system-font-with-a-point-size": "A selected system font with a point size.",
	"scaled-custom-font": "A selected custom font with size:, which scales with the body style.",
	"semantic-font": "A selected semantic font, such as .headline or .body.",
	"height-cap": "A selected frame with a fixed height.",
	"line-limit": "A selected lineLimit with a number.",
	"colour-by-state": "A selected foreground colour that a condition selects, such as red or green.",
	button: "A selected Button.",
	toggle: "A selected Toggle.",
};

const LABEL_OF: Record<string, string> = LABELS;

const rulesOf = (rows: readonly [string, RegExp][]): SiteRule[] =>
	rows.map(([kind, test]) => ({ kind, label: LABEL_OF[kind] ?? "", test }));

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const found = await work.sites(
			{ swift: rulesOf(INTERFACE_LINES) },
			{ overlap: "first", enclosing: {}, listed: 60 },
		);
		const leads = [...found.leads];
		const count = (kind: string) => found.counts.get(kind) ?? 0;
		const facts = {
			interfaceLinesSelected: found.matched,
			symbolImages: count("symbol-image"),
			assetImages: count("asset-image"),
			accessibilityModifiers: count("accessibility-modifier"),
			fixedSizeCustomFonts: count("fixed-size-custom-font"),
			systemFontsWithPointSize: count("system-font-with-a-point-size"),
			scaledCustomFonts: count("scaled-custom-font"),
			semanticFonts: count("semantic-font"),
			heightCaps: count("height-cap"),
			colourByState: count("colour-by-state"),
			filesWithUnknownLocation: found.filesWithUnknownLocation,
			filesScanned: found.filesScanned,
			linesSelected: found.linesSelected,
		};
		const directions: string[] = [...found.listedNote];
		if (facts.symbolImages + facts.assetImages > 0) {
			directions.push(
				`${String(facts.symbolImages + facts.assetImages)} image(s) selected against ${String(facts.accessibilityModifiers)} accessibility modifier(s) — for each image inside a Button, Toggle or tap gesture, read the whole view chain for a label or a Label title.`,
			);
		}
		if (facts.fixedSizeCustomFonts > 0) {
			directions.push(
				`${String(facts.fixedSizeCustomFonts)} custom font(s) with fixedSize: selected — these do not scale with the user's text size.`,
			);
		}
		if (facts.systemFontsWithPointSize > 0) {
			directions.push(
				`${String(facts.systemFontsWithPointSize)} system font(s) with a point size selected — fixed unless the size itself scales (a @ScaledMetric value); read where each size comes from. A custom font given size: scales with the body style and is not fixed.`,
			);
		}
		if (facts.heightCaps > 0) {
			directions.push(
				`${String(facts.heightCaps)} height cap(s) selected — a cap clips only when the container holds text.`,
			);
		}
		if (leads.length === 0) {
			directions.push(...found.noMatch);
		}
		return ctx.review({ leads, facts, directions });
	},
});
