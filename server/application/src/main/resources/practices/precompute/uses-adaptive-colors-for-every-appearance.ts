// Precompute HINTS for uses-adaptive-colors-for-every-appearance: the color expressions ADDED inside
// SwiftUI view types, each under the first shape its text shows, and for each named asset color whether
// its colorset carries a dark appearance. A shape is spelling, not a type: `.green` given to
// `foregroundStyle` is SwiftUI's context-dependent standard color, `Color(.green)` converts UIKit's fixed
// `UIColor.green`, and a name the app defines is whatever its definition says. Only the shapes whose
// spelling fixes the kind — components, white or black, semantic styles, materials, the accent — are
// counted as literal or adaptive; the rest are left to the review to classify. The role a color plays —
// content over a fill, or a background that bakes in one appearance — is the review's to read from the
// modifier chain.
import { readFile } from "node:fs/promises";
import path from "node:path";

import { globFilesSync } from "../lib/files.ts";
import { isJsonObject } from "../lib/practice-contract.ts";
import { countLabel, sampleNote, scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

const SWIFTUI_VIEW = /\b(?:View|App|Scene)\b/u;

// First match wins on a line, so the order runs from the shapes whose spelling fixes the kind to those it
// does not.
const COLORS: readonly SourcePattern[] = [
	[
		"fixed components",
		/\bColor\s*\(\s*(?:red|hue|white|\.sRGB|\.displayP3|hex)\s*:|\bColor\s*\(\s*"#|\bUIColor\s*\(\s*(?:red|white|hue|displayP3Red)\s*:/u,
	],
	["literal white/black", /\b(?:UI)?Color\.(?:white|black)\b|[:(,]\s*\.(?:white|black)\b/u],
	["UIKit color", /\bUIColor\s*\.\s*[a-z]\w*|\bColor\s*\(\s*uiColor:\s*\.[a-z]\w*/u],
	["named asset", /\bColor\s*\(\s*"[^"]+"\s*\)/u],
	["Color from a value", /\bColor\s*\(\s*\.[a-z]\w*/u],
	["semantic", /\.(?:primary|secondary|tertiary|quaternary)\b/u],
	["material", /\.(?:ultraThin|thin|regular|thick|ultraThick|bar)Material\b/u],
	["accent", /\.accentColor\b|[:(,]\s*\.tint\b(?!\s*\()/u],
	["SwiftUI Color member", /\bColor\s*\.\s*[a-z]\w*/u],
	[
		"member given to a color modifier",
		/\.(?:foregroundStyle|foregroundColor|background|fill|tint|stroke|strokeBorder|border)\s*\(\s*\.[a-z]\w*/u,
	],
];

/** The shapes whose spelling leaves the kind open: a type or a definition decides it. */
const TO_CLASSIFY = [
	"UIKit color",
	"Color from a value",
	"SwiftUI Color member",
	"member given to a color modifier",
] as const;

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
		if (h.pattern !== "named asset" && h.pattern !== "Color from a value") {
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
	const literals = countLabel(scan, "fixed components") + countLabel(scan, "literal white/black");
	const toClassify = TO_CLASSIFY.reduce((sum, label) => sum + countLabel(scan, label), 0);
	const systemAdaptive =
		countLabel(scan, "semantic") + countLabel(scan, "material") + countLabel(scan, "accent");
	const namedAssets = countLabel(scan, "named asset");
	const assetsWithoutDarkAppearance = hints.filter(
		(h) => h.flags.hasDarkAppearance === false,
	).length;
	const directions: string[] = [...sampleNote(scan)];
	if (literals > 0) {
		directions.push(
			`Found ${literals} literal colors, ${systemAdaptive} system-adaptive colors and ${namedAssets} named asset colors added in view types. For each literal, read the modifier chain. Text over a fill the same view sets is content. A literal background or text on a system background can fix one appearance. A translucent scrim over an image is content, not an adaptive screen background.`,
		);
	}
	if (toClassify > 0) {
		directions.push(
			`${toClassify} added line(s) name a color whose spelling does not settle its kind — a UIKit color, \`Color(.name)\`, a \`Color\` member or a member given to a color modifier. Read the type it resolves to and, for a name the app defines, its definition: SwiftUI's standard colors and UIKit's dynamic colors change with the appearance, UIKit's fixed constants do not.`,
		);
	}
	if (scan.hints.length > 0) {
		directions.push(
			"Each line is listed once, under the first shape its text shows: enumerate every color a listed line holds.",
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
			colorsToClassify: toClassify,
			assetsWithoutDarkAppearance,
			assetColorsInCheckout: colorsetsFound,
			filesWithoutCheckout: scan.filesWithoutCheckout,
			filesScanned: scan.filesScanned,
			linesAdded: scan.linesAdded,
		},
		directions,
	};
}
