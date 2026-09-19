// Precompute HINTS for makes-ui-accessible-by-default: the interface lines ADDED in Swift files that the
// closed list in the criteria asks about — icon-only controls, images, fixed-size fonts, height caps,
// colour-only state — and the accessibility modifiers added alongside them. The review reads the view
// chain; the script counts what there is to read and how many labels arrived with it.
import { scanSwift, type SwiftPattern } from "../lib/swift-scan.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

const INTERFACE_LINES: readonly SwiftPattern[] = [
	[
		"accessibility modifier",
		/\.accessibility(?:Label|Hint|Hidden|AddTraits|Value|Identifier)\s*\(/,
	],
	["Label with text", /\bLabel\s*\(\s*"/],
	["Image(systemName:)", /\bImage\s*\(\s*systemName:/],
	["Image(asset)", /\bImage\s*\(\s*(?:"|decorative:|[a-z]\w*\.)/],
	[
		"fixed-size font",
		/\.font\s*\(\s*\.system\s*\(\s*size:|\.font\s*\(\s*\.custom\s*\([^)]*size:\s*\d/,
	],
	[
		"semantic font",
		/\.font\s*\(\s*\.(?:largeTitle|title[23]?|headline|subheadline|body|callout|footnote|caption2?)\b/,
	],
	["height cap", /\.frame\s*\([^)]*\bheight:\s*\d/],
	["lineLimit", /\.lineLimit\s*\(\s*\d/],
	[
		"colour by state",
		/\.foregroundStyle\s*\(\s*[^)]*\?\s*\.(?:red|green|orange|yellow)|\.foregroundColor\s*\(\s*[^)]*\?\s*\.(?:red|green|orange|yellow)/,
	],
	["Button", /\bButton\s*[({]/],
	["Toggle", /\bToggle\s*\(/],
];

export default async function makesUiAccessibleByDefault(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const scan = await scanSwift(repoPath, diffFiles, INTERFACE_LINES, { maxHints: 60 });
	const count = (label: string) => scan.hints.filter((h) => h.pattern === label).length;
	const metrics = {
		interfaceLinesAdded: scan.hints.length,
		symbolImages: count("Image(systemName:)"),
		assetImages: count("Image(asset)"),
		accessibilityModifiers: count("accessibility modifier"),
		fixedSizeFonts: count("fixed-size font"),
		semanticFonts: count("semantic font"),
		heightCaps: count("height cap"),
		colourByState: count("colour by state"),
		filesWithoutCheckout: scan.filesWithoutCheckout,
	};
	const directions: string[] = [];
	if (metrics.symbolImages + metrics.assetImages > 0) {
		directions.push(
			`${metrics.symbolImages + metrics.assetImages} image(s) added against ${metrics.accessibilityModifiers} accessibility modifier(s) — for each image inside a Button, Toggle or tap gesture, read the whole view chain for a label or a Label title.`,
		);
	}
	if (metrics.fixedSizeFonts > 0) {
		directions.push(
			`${metrics.fixedSizeFonts} fixed-size font(s) added — a .custom font is scaled only with relativeTo:.`,
		);
	}
	if (metrics.heightCaps > 0) {
		directions.push(
			`${metrics.heightCaps} height cap(s) added — a cap clips only when the container holds text.`,
		);
	}
	return { hints: scan.hints, metrics, directions };
}
