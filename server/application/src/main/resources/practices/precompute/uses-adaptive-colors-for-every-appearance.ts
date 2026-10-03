// Precompute HINTS for uses-adaptive-colors-for-every-appearance: the color expressions ADDED inside
// SwiftUI view types, split into the criteria's literal, system-adaptive and named-asset shapes, and for
// each named asset color whether its colorset carries a dark appearance. The role a color plays —
// content over a fill, or a background that bakes in one appearance — is the review's to read from the
// modifier chain.
import { readFile } from "node:fs/promises";
import path from "node:path";

import { globFilesSync } from "../lib/files.ts";
import { isJsonObject } from "../lib/practice-contract.ts";
import { countLabel, sampleNote, scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

const SWIFTUI_VIEW = /\b(?:View|App|Scene)\b/u;

const COLORS: readonly SourcePattern[] = [
	["literal white/black", /\bColor\.(?:white|black)\b|[:(,]\s*\.(?:white|black)\b/u],
	[
		"literal RGB",
		/\bColor\s*\(\s*(?:red|hue|\.sRGB|\.displayP3)|\bUIColor\s*\(\s*red:|\bColor\s*\(\s*hex:|\bColor\s*\(\s*"#|\bColor\s*\(\s*uiColor:\s*\.(?:white|black)/u,
	],
	["gray literal", /\bColor\.gray\b|[:(,]\s*\.gray\b/u],
	[
		"semantic",
		/\.(?:primary|secondary|tertiary|quaternary|systemGray[2-6]?)\b|Color\s*\(\s*\.(?:systemBackground|secondarySystemBackground|tertiarySystemBackground|label|secondaryLabel|systemGroupedBackground|separator)\b/u,
	],
	["material", /\.(?:ultraThin|thin|regular|thick|ultraThick|bar)Material\b/u],
	["accent", /\.accentColor\b|\.tint\b|\bColor\.accent\b/u],
	["named asset", /\bColor\s*\(\s*"[^"]+"\s*\)|\bColor\s*\(\s*\.[a-z]\w*\s*\)/u],
];

/** Whether a colorset carries a dark appearance; "unknown" when it cannot be read as one. */
type DarkAppearance = boolean | "unknown";

function darkAppearanceOf(source: string): DarkAppearance {
	let parsed: unknown;
	try {
		parsed = JSON.parse(source);
	} catch {
		return "unknown";
	}
	if (!isJsonObject(parsed) || !Array.isArray(parsed.colors) || parsed.colors.length === 0) {
		return "unknown";
	}
	// Every entry is read before a missing dark appearance is a fact: one entry of another shape could
	// be the dark one.
	let dark = false;
	for (const entry of parsed.colors) {
		if (!isJsonObject(entry)) {
			return "unknown";
		}
		if (entry.appearances === undefined) {
			continue;
		}
		if (
			!Array.isArray(entry.appearances) ||
			!entry.appearances.every(
				(appearance) =>
					isJsonObject(appearance) &&
					typeof appearance.appearance === "string" &&
					typeof appearance.value === "string",
			)
		) {
			return "unknown";
		}
		dark ||= entry.appearances.some(
			(appearance) =>
				isJsonObject(appearance) &&
				appearance.appearance === "luminosity" &&
				appearance.value === "dark",
		);
	}
	return dark;
}

/** How many colorsets of the checkout are read; a name beyond them is unknown. */
const COLORSETS_READ = 200;

interface AssetColors {
	/** Every colorset path of the checkout by color name, read or not. */
	paths: Map<string, string[]>;
	/** The appearances of the colorsets read, by path. */
	read: Map<string, DarkAppearance>;
}

function colorNameOf(file: string): string {
	return (
		file
			.split("/")
			.at(-2)
			?.replace(/\.colorset$/u, "") ?? ""
	);
}

/**
 * The appearance of a named color, from its one colorset: the only one of exactly that name, else the
 * only one whose name differs in case alone. Two candidates anywhere in the checkout, or one not read,
 * leave it unknown, since nothing here says which target's catalog the view uses.
 */
function appearanceOf({ paths, read }: AssetColors, name: string): DarkAppearance {
	const exact = paths.get(name);
	const candidates =
		exact ??
		[...paths.entries()]
			.filter(([n]) => n.toLowerCase() === name.toLowerCase())
			.flatMap(([, files]) => files);
	const [only] = candidates;
	return candidates.length === 1 && only !== undefined ? (read.get(only) ?? "unknown") : "unknown";
}

async function assetColors(repoPath: string): Promise<AssetColors> {
	let all: string[] = [];
	try {
		all = globFilesSync("**/*.xcassets/**/*.colorset/Contents.json", repoPath);
	} catch {
		// no readable checkout: no colorset read
	}
	const paths = new Map<string, string[]>();
	for (const file of all) {
		const name = colorNameOf(file);
		paths.set(name, [...(paths.get(name) ?? []), file]);
	}
	const read = new Map<string, DarkAppearance>();
	for (const file of all.slice(0, COLORSETS_READ)) {
		let dark: DarkAppearance = "unknown";
		try {
			dark = darkAppearanceOf(await readFile(path.join(repoPath, file), "utf8"));
		} catch {
			// an unreadable colorset is not a fact about its appearances
		}
		read.set(file, dark);
	}
	return { paths, read };
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
		onlyInScope: true,
	});
	const assets = await assetColors(repoPath);
	const colorsetsFound = [...assets.paths.values()].reduce((sum, files) => sum + files.length, 0);
	const hints: Hint[] = scan.hints.map((h) => {
		if (h.pattern !== "named asset") {
			return h;
		}
		const name =
			/Color\s*\(\s*"(?<name>[^"]+)"/u.exec(h.context)?.groups?.name ??
			/Color\s*\(\s*\.(?<name>\w+)/u.exec(h.context)?.groups?.name ??
			"";
		return {
			...h,
			flags: { ...h.flags, asset: name, hasDarkAppearance: appearanceOf(assets, name) },
		};
	});
	const literals =
		countLabel(scan, "literal white/black") +
		countLabel(scan, "literal RGB") +
		countLabel(scan, "gray literal");
	const systemAdaptive =
		countLabel(scan, "semantic") + countLabel(scan, "material") + countLabel(scan, "accent");
	const namedAssets = countLabel(scan, "named asset");
	const assetsWithoutDarkAppearance = hints.filter(
		(h) => h.flags.hasDarkAppearance === false,
	).length;
	const directions: string[] = [...sampleNote(scan)];
	if (literals > 0) {
		directions.push(
			`${literals} literal color(s) added in view types against ${systemAdaptive} system-adaptive and ${namedAssets} named asset color(s); for each literal read the modifier chain to see whether it colors text over a fill the same view sets (content) or a background or text on a system background (one appearance baked in).`,
		);
	}
	if (assetsWithoutDarkAppearance > 0) {
		directions.push(
			`${assetsWithoutDarkAppearance} listed named asset color(s) whose colorset has no dark luminosity entry — read its color values and how it is used before judging whether it adapts.`,
		);
	}
	if (namedAssets > 0 && colorsetsFound > COLORSETS_READ) {
		directions.push(
			`The checkout holds ${String(colorsetsFound)} colorsets and the first ${String(COLORSETS_READ)} were read: a named color whose colorset was not read is unknown, not missing.`,
		);
	}
	return {
		hints,
		metrics: {
			literalColors: literals,
			systemAdaptiveColors: systemAdaptive,
			namedAssetColors: namedAssets,
			assetsWithoutDarkAppearance,
			assetColorsInCheckout: colorsetsFound,
			filesWithoutCheckout: scan.filesWithoutCheckout,
			filesScanned: scan.filesScanned,
			linesAdded: scan.linesAdded,
		},
		directions,
	};
}
