// Precompute HINTS for makes-ui-accessible-by-default: the interface lines ADDED in Swift files that the
// closed list in the criteria asks about — icon-only controls, images, fixed-size fonts, height caps,
// colour-only state — and the accessibility modifiers added alongside them. The review reads the view
// chain; the script counts what there is to read and how many labels arrived with it.
import { countLabel, scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

const INTERFACE_LINES: readonly SourcePattern[] = [
	[
		"accessibility modifier",
		/\.accessibility(?:Label|Hint|Hidden|AddTraits|Value|Identifier)\s*\(/u,
	],
	["Label with text", /\bLabel\s*\(\s*"/u],
	["Image(systemName:)", /\bImage\s*\(\s*systemName:/u],
	["Image(asset)", /\bImage\s*\(\s*(?:"|decorative:|[a-z]\w*\.)/u],
	[
		"fixed-size font",
		/\.font\s*\(\s*\.system\s*\(\s*size:|\.font\s*\(\s*\.custom\s*\([^)]*size:\s*\d/u,
	],
	[
		"semantic font",
		/\.font\s*\(\s*\.(?:largeTitle|title[23]?|headline|subheadline|body|callout|footnote|caption2?)\b/u,
	],
	["height cap", /\.frame\s*\([^)]*\bheight:\s*\d/u],
	["lineLimit", /\.lineLimit\s*\(\s*\d/u],
	[
		"colour by state",
		/\.foregroundStyle\s*\(\s*[^)]*\?\s*\.(?:red|green|orange|yellow)|\.foregroundColor\s*\(\s*[^)]*\?\s*\.(?:red|green|orange|yellow)/u,
	],
	["Button", /\bButton\s*[({]/u],
	["Toggle", /\bToggle\s*\(/u],
];

export default async function makesUiAccessibleByDefault(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const scan = await scanAddedLines(repoPath, diffFiles, {
		languages: ["swift"],
		patterns: INTERFACE_LINES,
		maxHints: 60,
	});
	const count = (label: string) => countLabel(scan, label);
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
		filesScanned: scan.filesScanned,
		linesAdded: scan.linesAdded,
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
