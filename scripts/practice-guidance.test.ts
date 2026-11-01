/**
 * The curated style of the bundled practice guidance under `practices/guidance/`. The server already
 * refuses unsafe markup, a missing file, and a file outside the practice's own folder. It accepts any
 * safe picture and any guide, and it never sees a file that nothing references. These checks cover
 * that gap. `docs/contributor/practice-visuals.md` is the style guide that they enforce.
 */
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { fromMarkdown } from "mdast-util-from-markdown";

import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";

const RESOURCES = fileURLToPath(
	new URL("../server/application/src/main/resources/", import.meta.url),
);
const GUIDANCE = "practices/guidance/";

// The classes the webapp maps to theme tokens (`PRACTICE_SVG_THEME` in `PracticeVisual.tsx`).
const THEME_CLASSES = new Set([
	"pv-fill-ink",
	"pv-fill-muted",
	"pv-fill-surface",
	"pv-fill-accent",
	"pv-fill-accent-soft",
	"pv-stroke-ink",
	"pv-stroke-muted",
	"pv-stroke-line",
	"pv-stroke-accent",
	// The code font, for one code token.
	"pv-mono",
]);
const GUIDE_SECTIONS = ["How to do it", "When it does not apply", "Common mistakes", "Sources"];
const SOURCES = { min: 2, max: 4 };
const DESCRIPTION_LENGTH = 250;
// The words of a visual: titles, labels and examples together. A guide figure has no limit.
const VISUAL_WORDS = 24;
// The style guide's one value set for each drawing attribute. An attribute that is not listed is free.
const DRAWING_VALUES: Record<string, ReadonlySet<string>> = {
	"font-size": new Set(["15", "16"]),
	"font-weight": new Set(["600"]),
	"stroke-width": new Set(["1.5", "2"]),
	rx: new Set(["3", "8", "12"]),
	// Opacity gives a different contrast in each theme, so no value is allowed.
	opacity: new Set(),
	"fill-opacity": new Set(),
	"stroke-opacity": new Set(),
};
// A screen reader already announces an image, so this opening repeats it.
const REDUNDANT_OPENING = /^(?:image|picture) of\b/iu;
// The server accepts only well-formed SVG with no style element, so a flat scan finds every attribute.
const ATTRIBUTE = /\s(?<name>[\w:-]+)=(?<quote>["'])(?<value>.*?)\k<quote>/gsu;
const TEXT = /<text\b[^>]*>(?<content>.*?)<\/text>/gsu;
const TAG = /<[^>]*>/gu;

interface Guidance {
	visual?: { file: string; alt: string };
	guide?: string;
}

type Markdown = ReturnType<typeof fromMarkdown>;
type MarkdownNode = Markdown | Markdown["children"][number];

const read = (file: string) => readFileSync(path.join(RESOURCES, file), "utf8");

function attributesOf(svg: string) {
	return [...svg.matchAll(ATTRIBUTE)].map(({ groups }) => ({
		name: groups?.name ?? "",
		value: groups?.value ?? "",
	}));
}

/** The root viewBox as `[x, y, width, height]`. */
function canvasOf(svg: string): number[] {
	const viewBox = attributesOf(svg).find(({ name }) => name === "viewBox")?.value ?? "";
	return viewBox.trim().split(/\s+/u).map(Number);
}

/** Colors that do not follow the theme: a class other than `pv-*`, or a fill or stroke other than none. */
function fixedColors(svg: string): string[] {
	return attributesOf(svg).flatMap(({ name, value }) => {
		if (name === "class") {
			return value
				.trim()
				.split(/\s+/u)
				.filter((token) => !THEME_CLASSES.has(token))
				.map((token) => `class "${token}"`);
		}
		return (name === "fill" || name === "stroke") && value !== "none" ? [`${name}="${value}"`] : [];
	});
}

/** Drawing attributes with a value outside the style guide's set, such as a third text size. */
function offGrammar(svg: string): string[] {
	return attributesOf(svg).flatMap(({ name, value }) => {
		const allowed = DRAWING_VALUES[name];
		return allowed === undefined || allowed.has(value) ? [] : [`${name}="${value}"`];
	});
}

/** The words of every text element, with the markup of a tspan removed. */
function wordsOf(svg: string): string[] {
	return [...svg.matchAll(TEXT)].flatMap(({ groups }) =>
		(groups?.content ?? "").replaceAll(TAG, " ").split(/\s+/u).filter(Boolean),
	);
}

function assertVisualWords(file: string, svg: string) {
	const words = wordsOf(svg);
	assert.ok(
		words.length <= VISUAL_WORDS,
		`${file}: ${VISUAL_WORDS} words or fewer, titles, labels and examples together, not ${words.length}`,
	);
}

/** The checks that every bundled picture and every starter template shares. */
function assertPicture(file: string, svg: string, maxHeight: number) {
	const [x, y, width, height] = canvasOf(svg);
	assert.deepEqual([x, y, width], [0, 0, 640], `${file}: viewBox "0 0 640 H"`);
	assert.ok(
		(height ?? Number.NaN) <= maxHeight,
		`${file}: the viewBox height is ${maxHeight} or less`,
	);
	assert.deepEqual(fixedColors(svg), [], `${file}: color with pv-* classes only`);
	assert.deepEqual(
		offGrammar(svg),
		[],
		`${file}: sizes, weights, strokes and radii of the style guide`,
	);
}

function assertDescription(alt: string, label: string) {
	assert.doesNotMatch(alt, REDUNDANT_OPENING, label);
	assert.ok(
		alt.length <= DESCRIPTION_LENGTH,
		`${label}: ${DESCRIPTION_LENGTH} characters or fewer`,
	);
}

function* nodesOf(node: MarkdownNode): Generator<MarkdownNode> {
	yield node;
	if ("children" in node) {
		for (const child of node.children) {
			yield* nodesOf(child);
		}
	}
}

function textOf(node: MarkdownNode): string {
	return [...nodesOf(node)].map((child) => (child.type === "text" ? child.value : "")).join("");
}

/** The level-2 headings, the links under the last of them, and every image. */
function outlineOf(markdown: string) {
	const tree = fromMarkdown(markdown);
	const headings = tree.children.flatMap((node, index) =>
		node.type === "heading" && node.depth === 2 ? [{ text: textOf(node), index }] : [],
	);
	const lastSection = tree.children.slice((headings.at(-1)?.index ?? tree.children.length) + 1);
	return {
		sections: headings.map(({ text }) => text),
		lastSectionLinks: lastSection.flatMap((node) =>
			[...nodesOf(node)].flatMap((link) => (link.type === "link" ? [link.url] : [])),
		),
		images: [...nodesOf(tree)].flatMap((image) =>
			image.type === "image" ? [{ url: image.url, alt: image.alt ?? "" }] : [],
		),
	};
}

const figureFile = (guide: string, url: string) => path.posix.join(path.posix.dirname(guide), url);

/** The files under the guidance folder that no practice shows as its visual, its guide, or a figure. */
function unreferenced(
	files: string[],
	practices: Guidance[],
	readFile: (file: string) => string,
): string[] {
	const referenced = new Set(
		practices.flatMap(({ visual, guide }) => [
			...(visual === undefined ? [] : [visual.file]),
			...(guide === undefined
				? []
				: [guide, ...outlineOf(readFile(guide)).images.map(({ url }) => figureFile(guide, url))]),
		]),
	);
	return files.filter((file) => !referenced.has(file));
}

const bundledFiles = readdirSync(path.join(RESOURCES, GUIDANCE), {
	recursive: true,
	withFileTypes: true,
})
	.filter((entry) => entry.isFile())
	.map((entry) =>
		path.relative(RESOURCES, path.join(entry.parentPath, entry.name)).split(path.sep).join("/"),
	);

const bundledPractices = asArray(
	asRecord(parseJson(read("practices/default-catalog.json")), "catalog").groups,
	"groups",
)
	.flatMap((group) => asArray(asRecord(group, "group").practices, "practices"))
	.map((entry) => {
		const practice = asRecord(entry, "practice");
		const slug = asString(practice.slug, "practice slug");
		const visual =
			practice.visualResource === undefined
				? undefined
				: asRecord(practice.visualResource, `${slug} visual resource`);
		return {
			slug,
			visual:
				visual === undefined
					? undefined
					: { file: asString(visual.file, slug), alt: asString(visual.alt, slug) },
			guide:
				practice.guideResource === undefined
					? undefined
					: asString(practice.guideResource, `${slug} guide resource`),
		};
	});

await test("every file under practices/guidance/ belongs to a practice", () => {
	assert.ok(bundledFiles.length > 0, `${GUIDANCE} has files`);
	assert.deepEqual(
		unreferenced(bundledFiles, bundledPractices, read),
		[],
		"reference each file from default-catalog.json or a guide, or delete it",
	);
});

for (const { slug, visual, guide } of bundledPractices) {
	if (visual !== undefined) {
		await test(`${slug}: the visual follows the style guide`, () => {
			assertPicture(visual.file, read(visual.file), 320);
			assertVisualWords(visual.file, read(visual.file));
			assertDescription(visual.alt, `${slug}: the visual's description`);
		});
	}
	if (guide !== undefined) {
		await test(`${slug}: the guide and its figures follow the style guide`, () => {
			const { sections, lastSectionLinks, images } = outlineOf(read(guide));
			assert.deepEqual(sections, GUIDE_SECTIONS, `${guide}: the level-2 headings, in order`);
			assert.ok(
				lastSectionLinks.length >= SOURCES.min && lastSectionLinks.length <= SOURCES.max,
				`${guide}: Sources links ${SOURCES.min} to ${SOURCES.max} sources`,
			);
			assert.deepEqual(
				lastSectionLinks.filter((url) => !url.startsWith("https://")),
				[],
				`${guide}: every source is an https link`,
			);
			for (const { url, alt } of images) {
				const file = figureFile(guide, url);
				assertPicture(file, read(file), 480);
				assertDescription(alt, `${file}: the figure's description`);
			}
		});
	}
}

// The admin docs offer these starters, so they show the same grammar as the bundled pictures.
const STARTERS = fileURLToPath(new URL("../docs/static/", import.meta.url));
const starters = readdirSync(STARTERS).filter((name) =>
	/^practice-visual-template.*\.svg$/u.test(name),
);

await test("the starter templates follow the style guide", () => {
	assert.ok(starters.length > 0, "docs/static/ has starter templates");
	for (const starter of starters) {
		const svg = readFileSync(path.join(STARTERS, starter), "utf8");
		assertPicture(starter, svg, 320);
		assertVisualWords(starter, svg);
	}
});

// The checks above pass silently if their helpers see nothing, so each helper is proved on a failure.

await test("the color check finds a class outside the theme and a fixed color", () => {
	const svg = `<svg viewBox="0 0 640 320">
		<rect class="pv-fill-surface brand" fill='#d33'/>
		<path class="pv-stroke-accent" fill="none" stroke="currentColor"/>
	</svg>`;
	assert.deepEqual(fixedColors(svg), ['class "brand"', 'fill="#d33"', 'stroke="currentColor"']);
	assert.deepEqual(canvasOf(svg), [0, 0, 640, 320]);
});

await test("the grammar check finds an off size, weight, stroke, radius and any opacity", () => {
	const svg = `<svg viewBox="0 0 640 320">
		<text font-size="15" font-weight="600"/>
		<text font-size="20" font-weight="700"/>
		<rect rx="8" stroke-width="1.5"/>
		<rect rx="6" stroke-width="3" opacity="0.4"/>
		<path fill-opacity="1" stroke-opacity="0.5"/>
	</svg>`;
	assert.deepEqual(offGrammar(svg), [
		'font-size="20"',
		'font-weight="700"',
		'rx="6"',
		'stroke-width="3"',
		'opacity="0.4"',
		'fill-opacity="1"',
		'stroke-opacity="0.5"',
	]);
});

await test("the word count reads every text and tspan", () => {
	const svg = `<svg viewBox="0 0 640 320">
		<text font-size="16">A sprawling change</text>
		<text font-size="15">Read <tspan class="pv-mono">try?</tspan> end to end</text>
		<title>Not a label</title>
	</svg>`;
	assert.deepEqual(wordsOf(svg), ["A", "sprawling", "change", "Read", "try?", "end", "to", "end"]);
});

await test("the outline reads headings, the last section's links, and images", () => {
	const markdown = [
		"## How to do it",
		"[Not a source](https://example.com/a) ![A figure.](figures/a.svg)",
		"## Sources",
		"- [Paper](http://example.com/b)",
	].join("\n\n");
	assert.deepEqual(outlineOf(markdown), {
		sections: ["How to do it", "Sources"],
		lastSectionLinks: ["http://example.com/b"],
		images: [{ url: "figures/a.svg", alt: "A figure." }],
	});
});

await test("the reference check finds a file that no practice shows", () => {
	const folder = `${GUIDANCE}a-practice/`;
	const practices = [
		{ visual: { file: `${folder}visual.svg`, alt: "" }, guide: `${folder}guide.md` },
	];
	const files = [`${folder}visual.svg`, `${folder}guide.md`, `${folder}figures/a.svg`];
	assert.deepEqual(
		unreferenced(files, practices, () => "![A figure.](figures/a.svg)"),
		[],
	);
	assert.deepEqual(
		unreferenced([...files, `${folder}figures/b.svg`], practices, () => ""),
		[`${folder}figures/a.svg`, `${folder}figures/b.svg`],
	);
});
