import type { PracticeGuide, PracticeVisual } from "@/api/types.gen";

import { generateSlug } from "./constants";

/**
 * The limits the server enforces on a visual and a guide (`PracticeGuidanceRules`). The editor
 * checks only what it can see without parsing the SVG; the server stays the authority.
 */
export const MAX_SVG_BYTES = 64 * 1024;
export const MAX_SVG_SIZE = `${MAX_SVG_BYTES / 1024} KB`;
export const MAX_VISUAL_ALT_LENGTH = 300;
export const MAX_GUIDE_LENGTH = 12_000;
export const MAX_GUIDE_FIGURES = 4;

/** The description a new figure line starts with, which the author must replace before saving. */
export const FIGURE_PLACEHOLDER = "Describe the figure";

/** The form holds an absent visual or guide as empty text, so its controls stay controlled. */
export const NO_VISUAL: PracticeVisual = { svg: "", alt: "" };
export const NO_GUIDE: PracticeGuide = { markdown: "", figures: {} };

export function hasVisual(visual: PracticeVisual): boolean {
	return visual.svg.trim().length > 0;
}

export function hasGuide(guide: PracticeGuide): boolean {
	return guide.markdown.trim().length > 0 || Object.keys(guide.figures).length > 0;
}

/** The first problem with SVG markup that the browser can see without parsing it. */
export function svgProblem(svg: string): "not-svg" | "too-large" | undefined {
	if (!/<svg[\s>]/u.test(svg)) {
		return "not-svg";
	}
	// The server counts UTF-8 bytes, not characters.
	return new TextEncoder().encode(svg).byteLength > MAX_SVG_BYTES ? "too-large" : undefined;
}

/**
 * The markup of an uploaded file, or why it cannot be used. The size is checked before the file is
 * read, so a large file of any kind is never read whole.
 */
export async function readSvgFile(file: File): Promise<{ svg: string } | { problem: string }> {
	if (file.size > MAX_SVG_BYTES) {
		return {
			problem: `“${file.name}” is larger than ${MAX_SVG_SIZE}. Simplify the drawing, then try again.`,
		};
	}
	const svg = await file.text();
	return svgProblem(svg) === "not-svg"
		? { problem: `“${file.name}” is not an SVG file. Choose an SVG file.` }
		: { svg };
}

/** A figure name read off the uploaded file's name, made unique with a -2, -3 suffix. */
export function figureName(fileName: string, taken: readonly string[]): string {
	// The slug's 64-character cut can end on a hyphen, which a figure name may not.
	const stem = generateSlug(fileName.replace(/\.svg$/iu, "")).replace(/-$/u, "") || "figure";
	let name = stem;
	for (let suffix = 2; taken.includes(name); suffix += 1) {
		name = `${stem}-${suffix}`;
	}
	return name;
}

export function figureLine(name: string): string {
	return `![${FIGURE_PLACEHOLDER}](figures/${name}.svg)`;
}

/** Puts a block on a paragraph of its own: an image glued to the sentence before it renders inline. */
export function insertBlock(markdown: string, block: string, position: number): string {
	const before = markdown.slice(0, position);
	const after = markdown.slice(position);
	const lead = "\n\n".slice(before.length === 0 ? 2 : trailingBreaks(before));
	const trail = "\n\n".slice(after.length === 0 ? 2 : leadingBreaks(after));
	return `${before}${lead}${block}${trail}${after}`;
}

function trailingBreaks(text: string): number {
	return /\n*$/u.exec(text)?.[0].length ?? 0;
}

function leadingBreaks(text: string): number {
	return /^\n*/u.exec(text)?.[0].length ?? 0;
}

function figureImages(name: string): RegExp {
	// A figure name holds only lowercase letters, digits and hyphens, so it needs no escaping.
	return new RegExp(String.raw`!\[([^\]]*)\]\(figures/${name}\.svg(?:\s+"[^"]*")?\)`, "gu");
}

/** The guide's text without any line that shows the figure, so the server never meets a dangling one. */
export function withoutFigure(markdown: string, name: string): string {
	const images = figureImages(name);
	return (
		markdown
			.split("\n")
			// Only the lines that showed the figure change, so a hard break elsewhere keeps its spaces.
			.flatMap((line) => {
				const rest = line.replace(images, "");
				if (rest === line) {
					return [line];
				}
				return rest.trim().length === 0 ? [] : [rest.replaceAll(/ {2,}/gu, " ").trimEnd()];
			})
			.join("\n")
			.replaceAll(/\n{3,}/gu, "\n\n")
			.replace(/^\n+/u, "")
	);
}

/** What the guide's text says the figure shows, from the first line that shows it. */
export function figureDescription(markdown: string, name: string): string | undefined {
	const description = figureImages(name).exec(markdown)?.[1]?.trim();
	return description === undefined || description.length === 0 ? undefined : description;
}

/** Whether a figure line still carries the description the editor inserted for the author to replace. */
export function hasFigurePlaceholder(markdown: string): boolean {
	return markdown.includes(`![${FIGURE_PLACEHOLDER}](`);
}
