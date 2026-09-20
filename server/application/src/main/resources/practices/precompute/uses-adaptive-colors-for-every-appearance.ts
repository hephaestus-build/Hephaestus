// Precompute HINTS for uses-adaptive-colors-for-every-appearance: the color expressions ADDED inside
// SwiftUI view types, split into the criteria's literal and adaptive shapes, and for each named asset
// color whether its colorset carries a dark appearance. The role a literal plays — content over a fill,
// or a background that bakes in one appearance — is the review's to read from the modifier chain.
import { readFile } from "node:fs/promises";
import path from "node:path";

import { globFilesSync } from "../lib/files.ts";
import { countLabel, scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

const SWIFTUI_VIEW = /\b(?:View|App|Scene)\b/u;

const COLORS: readonly SourcePattern[] = [
	["literal white/black", /\bColor\.(?:white|black)\b|[:(,]\s*\.(?:white|black)\b/u],
	[
		"literal RGB",
		/\bColor\s*\(\s*(?:red|hue|\.sRGB|\.displayP3)|\bUIColor\s*\(\s*red:|\bColor\s*\(\s*hex:|\bColor\s*\(\s*"#|\bColor\s*\(\s*uiColor:\s*\.(?:white|black)/u,
	],
	["gray literal", /\bColor\.gray\b|[:(,]\s*\.gray\b|\bColor\s*\(\s*\.systemGray\d?\s*\)/u],
	[
		"semantic",
		/\.(?:primary|secondary|tertiary|quaternary)\b|Color\s*\(\s*\.(?:systemBackground|secondarySystemBackground|tertiarySystemBackground|label|secondaryLabel|systemGroupedBackground|separator)\b/u,
	],
	["material", /\.(?:ultraThin|thin|regular|thick|ultraThick|bar)Material\b/u],
	["accent", /\.accentColor\b|\.tint\b|\bColor\.accent\b/u],
	["named asset", /\bColor\s*\(\s*"[^"]+"\s*\)|\bColor\s*\(\s*\.[a-z]\w*\s*\)/u],
];

/** Named asset colors of the checkout and whether each colorset has a dark appearance. */
async function assetColors(repoPath: string): Promise<Map<string, boolean>> {
	const found = new Map<string, boolean>();
	for (const file of globFilesSync("**/*.xcassets/**/*.colorset/Contents.json", repoPath).slice(
		0,
		200,
	)) {
		const name =
			file
				.split("/")
				.at(-2)
				?.replace(/\.colorset$/u, "") ?? "";
		try {
			const text = await readFile(path.join(repoPath, file), "utf8");
			found.set(name, /"appearance"\s*:\s*"luminosity"[^}]*"value"\s*:\s*"dark"/u.test(text));
		} catch {
			// an unreadable colorset is not a fact about its appearances
		}
	}
	return found;
}

export default async function usesAdaptiveColorsForEveryAppearance(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const scan = await scanAddedLines(repoPath, diffFiles, {
		languages: ["swift"],
		patterns: COLORS,
		scope: SWIFTUI_VIEW,
		maxHints: 60,
	});
	const assets = await assetColors(repoPath);
	const hints: Hint[] = scan.hints.map((h) => {
		if (h.pattern !== "named asset") {
			return h;
		}
		const name =
			/Color\s*\(\s*"(?<name>[^"]+)"/u.exec(h.context)?.groups?.name ??
			/Color\s*\(\s*\.(?<name>\w+)/u.exec(h.context)?.groups?.name ??
			"";
		const dark = [...assets.entries()].find(
			([n]) => n === name || n.toLowerCase() === name.toLowerCase(),
		)?.[1];
		return {
			...h,
			flags: { ...h.flags, asset: name, hasDarkAppearance: dark ?? "unknown" },
		};
	});
	const literals =
		countLabel(scan, "literal white/black") +
		countLabel(scan, "literal RGB") +
		countLabel(scan, "gray literal");
	const adaptive =
		countLabel(scan, "semantic") +
		countLabel(scan, "material") +
		countLabel(scan, "accent") +
		countLabel(scan, "named asset");
	const singleAppearanceAssets = hints.filter((h) => h.flags.hasDarkAppearance === false).length;
	const directions: string[] = [];
	if (literals > 0) {
		directions.push(
			`${literals} literal color(s) added in view types against ${adaptive} adaptive one(s); for each literal read the modifier chain to see whether it colors text over a fill the same view sets (content) or a background or text on a system background (one appearance baked in).`,
		);
	}
	if (singleAppearanceAssets > 0) {
		directions.push(
			`${singleAppearanceAssets} named asset color(s) whose colorset has no dark appearance — a literal in disguise.`,
		);
	}
	return {
		hints: hints.slice(0, 40),
		metrics: {
			literalColors: literals,
			adaptiveColors: adaptive,
			singleAppearanceAssets,
			assetColorsInCheckout: assets.size,
			filesWithoutCheckout: scan.filesWithoutCheckout,
		},
		directions,
	};
}
