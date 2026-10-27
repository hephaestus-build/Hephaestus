/** The page rules of `docs/contributor/documentation-guide.md` that a machine can hold. */
import { statSync } from "node:fs";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { parse } from "yaml";

const repositoryRoot = path.resolve(import.meta.dirname, "..");

const GUIDES = ["user", "admin", "contributor"] as const;
export type Guide = (typeof GUIDES)[number];
const PAGE_TYPES: Record<Guide, readonly string[]> = {
	user: ["overview", "tutorial", "feature", "reference"],
	admin: ["overview", "tutorial", "how-to", "reference", "explanation", "troubleshooting"],
	contributor: ["overview", "how-to", "reference", "explanation"],
};
// Every page of these guides declares a type, so each page answers to every rule.
const FULLY_CHECKED = new Set<Guide>(["user"]);

const CALLOUTS = new Set(["note", "tip", "caution"]);
const SECTION_NAMES = new Map([
	["Prerequisites", "Before you start"],
	["Requirements", "Before you start"],
	["Before you begin", "Before you start"],
	["Verify", "Check that it worked"],
	["Verification", "Check that it worked"],
	["Validation", "Check that it worked"],
	["Common problems", "Troubleshooting"],
	["Known issues", "Troubleshooting"],
	["See also", "Next steps"],
	["Related", "Next steps"],
	["What's next", "Next steps"],
	["Further reading", "Next steps"],
]);
// The section that proves a page type reached its result.
const CLOSING_SECTION = new Map([
	["how-to", "Check that it worked"],
	["tutorial", "Next steps"],
]);
const MAX_DESCRIPTION = 160;
// What a user cannot run or open: the repository's commands and its top-level source trees.
const REPOSITORY_TOOLING =
	/^(?:vp|pnpm|npm|npx|\.\/gradlew|gradlew)\s|^(?:server|webapp|extension|docs|scripts|deploy|docker|\.github)\//u;
// An inline link, a reference definition or an HTML anchor whose target is in the contributor guide.
const CONTRIBUTOR_LINK = /(?:\]\(|\]:\s+|href=["'])(?:[^)\s"']*\/)?contributor\//u;

const SCREENSHOTS = "docs/static/img/user";
const COLOR_SCHEMES = ["light", "dark"];
// GitLab's alt text limit; a longer description belongs in the text around the image.
const MAX_ALT = 155;
// The capture enforces the same budget; a hand-edited or stale image meets it here.
const MAX_IMAGE_BYTES = 150_000;

interface Line {
	number: number;
	text: string;
}

/** Splits a page into its front matter and the body lines outside fenced code. */
function readPage(source: string): {
	frontMatter: unknown;
	frontMatterError?: string;
	frontMatterLines: number;
	body: Line[];
} {
	const lines = source.split(/\r?\n/u);
	let frontMatterLines = 0;
	let frontMatter: unknown = undefined;
	let frontMatterError: string | undefined;
	if (lines[0] === "---") {
		const end = lines.indexOf("---", 1);
		if (end > 0) {
			try {
				frontMatter = parse(lines.slice(1, end).join("\n"));
			} catch (error) {
				frontMatterError = error instanceof Error ? error.message : String(error);
			}
			frontMatterLines = end + 1;
		}
	}
	const body: Line[] = [];
	let fence: string | undefined;
	for (const [index, text] of lines.entries()) {
		if (index < frontMatterLines) {
			continue;
		}
		// A closing fence has no info string and is at least as long as the opening one.
		const marker = (
			fence === undefined ? /^\s*(?<fence>`{3,}|~{3,})/u : /^\s*(?<fence>`{3,}|~{3,})\s*$/u
		).exec(text)?.groups?.fence;
		if (marker !== undefined && (fence === undefined || marker.startsWith(fence))) {
			fence = fence === undefined ? marker : undefined;
			continue;
		}
		if (fence === undefined) {
			body.push({ number: index + 1, text });
		}
	}
	return { frontMatter, frontMatterError, frontMatterLines, body };
}

function field(frontMatter: unknown, key: string): unknown {
	return typeof frontMatter === "object" && frontMatter !== null
		? Reflect.get(frontMatter, key)
		: undefined;
}

function textField(frontMatter: unknown, key: string): string | undefined {
	const value = field(frontMatter, key);
	return typeof value === "string" ? value : undefined;
}

interface Problem {
	line: number;
	message: string;
}

function frontMatterProblems(frontMatter: unknown, guide: Guide): Problem[] {
	const problems: Problem[] = [];
	const report = (message: string) => problems.push({ line: 1, message });
	for (const key of ["title", "description", "page_type"]) {
		const value = field(frontMatter, key);
		if (value === undefined || value === null) {
			const choices = key === "page_type" ? `: ${PAGE_TYPES[guide].join(", ")}` : "";
			report(`Add a ${key} to the front matter${choices}.`);
		} else if (typeof value !== "string") {
			report(`Write the ${key} as text.`);
		}
	}
	const description = textField(frontMatter, "description");
	if (description !== undefined && description.length > MAX_DESCRIPTION) {
		report(`Shorten the description to ${MAX_DESCRIPTION} characters (${description.length}).`);
	}
	if (field(frontMatter, "id") !== undefined) {
		report("Remove the id. The file name is the ID.");
	}
	return problems;
}

function lineProblems({ number, text }: Line, guide: Guide, pageType?: string): Problem[] {
	const problems: string[] = [];
	const heading = /^(?<level>#{1,6})\s+(?<name>.+?)\s*$/u.exec(text)?.groups;
	if (heading?.level === "#") {
		problems.push("Remove the # heading. The front matter title is the page heading.");
	}
	const replacement = SECTION_NAMES.get(heading?.name ?? "");
	if (replacement !== undefined) {
		problems.push(`Name the section "${replacement}".`);
	}
	if (guide === "user" && pageType !== "reference" && CONTRIBUTOR_LINK.test(text)) {
		problems.push(
			"Link the user or admin guide. Only a reference page cites the contributor guide.",
		);
	}
	if (guide === "user" && /!\[[^\]]*\]\(/u.test(text)) {
		problems.push("Show the product with <Screenshot>, which has a light and a dark image.");
	}
	for (const [, code] of guide === "user" ? text.matchAll(/`(?<code>[^`]+)`/gu) : []) {
		if (code !== undefined && REPOSITORY_TOOLING.test(code)) {
			problems.push(`Remove the repository command or path \`${code}\` from the user guide.`);
		}
	}
	return problems.map((message) => ({ line: number, message }));
}

function sectionProblems(body: Line[], firstLine: number, pageType?: string): Problem[] {
	const problems: Problem[] = [];
	const sections = body.flatMap(({ number, text }) => {
		const name = /^##\s+(?<name>.+?)\s*$/u.exec(text)?.groups?.name;
		return name === undefined ? [] : [{ number, text: name }];
	});
	const troubleshooting = sections.findIndex((section) => section.text === "Troubleshooting");
	if (troubleshooting !== -1 && troubleshooting !== sections.length - 1) {
		const line = sections[troubleshooting]?.number ?? firstLine;
		problems.push({ line, message: "Move the Troubleshooting section to the end." });
	}
	const required = pageType === undefined ? undefined : CLOSING_SECTION.get(pageType);
	if (pageType === undefined || required === undefined) {
		return problems;
	}
	if (!body.some((line) => /^\s*\d+\.\s/u.test(line.text))) {
		problems.push({ line: firstLine, message: `A ${pageType} page needs numbered steps.` });
	}
	if (!sections.some((section) => section.text === required)) {
		problems.push({
			line: firstLine,
			message: `A ${pageType} page needs a "${required}" section.`,
		});
	}
	return problems;
}

export function checkPage(file: string, guide: Guide, source: string): string[] {
	const { frontMatter, frontMatterError, frontMatterLines, body } = readPage(source);
	if (frontMatterError !== undefined) {
		return [`${file}:1: Fix the front matter: ${frontMatterError}`];
	}
	const problems: Problem[] = [];
	const pageType = textField(frontMatter, "page_type");
	if (pageType !== undefined && !PAGE_TYPES[guide].includes(pageType)) {
		problems.push({
			line: 1,
			message: `page_type "${pageType}" is not a ${guide} guide type: ${PAGE_TYPES[guide].join(", ")}.`,
		});
	}
	for (const { number, text } of body) {
		const callout = /^\s*:{3,}(?<type>\w+)/u.exec(text)?.groups?.type;
		if (callout !== undefined && !CALLOUTS.has(callout)) {
			problems.push({
				line: number,
				message: `Use a note, tip or caution callout, not "${callout}".`,
			});
		}
	}
	if (FULLY_CHECKED.has(guide)) {
		problems.push(
			...frontMatterProblems(frontMatter, guide),
			...body.flatMap((line) => lineProblems(line, guide, pageType)),
		);
	}
	if (FULLY_CHECKED.has(guide) || pageType !== undefined) {
		problems.push(...sectionProblems(body, frontMatterLines + 1, pageType));
	}
	return problems.map(({ line, message }) => `${file}:${line}: ${message}`);
}

/**
 * Each `<Screenshot>` names its image with a literal `src` and describes it in a literal `alt`, and
 * the image exists in both color schemes within the size budget. MDX is not type-checked, so the
 * component's required props hold only here.
 */
export function screenshotProblems(
	file: string,
	source: string,
	sizeOf: (path: string) => number | undefined,
): string[] {
	const problems: string[] = [];
	for (const match of source.matchAll(/<Screenshot\b[^>]*>/gu)) {
		const report = (message: string) =>
			problems.push(`${file}:${source.slice(0, match.index).split("\n").length}: ${message}`);
		const alt = /\balt="(?<alt>[^"]*)"/u.exec(match[0])?.groups?.alt?.trim() ?? "";
		if (alt === "") {
			report("Give the <Screenshot> a literal alt text that says what the image shows.");
		} else if (alt.length > MAX_ALT) {
			report(`Shorten the alt text to ${MAX_ALT} characters (${alt.length}).`);
		}
		const src = /\bsrc="(?<src>[^"]+)"/u.exec(match[0])?.groups?.src;
		if (src === undefined) {
			report('Give the <Screenshot> a literal src, such as src="practice-profile/overview".');
			continue;
		}
		for (const scheme of COLOR_SCHEMES) {
			const image = `${SCREENSHOTS}/${src}.${scheme}.webp`;
			const size = sizeOf(image);
			if (size === undefined) {
				report(`${image} does not exist. Run vp run generate:docs-screenshots.`);
			} else if (size > MAX_IMAGE_BYTES) {
				report(`${image} is ${size} bytes, over the ${MAX_IMAGE_BYTES}-byte budget.`);
			}
		}
	}
	return problems;
}

async function pagesOf(directory: string): Promise<string[]> {
	const entries = await readdir(path.resolve(repositoryRoot, directory), {
		recursive: true,
		withFileTypes: true,
	});
	return (
		entries
			// A leading `_` marks a partial that pages import; Docusaurus does not publish it.
			.filter(
				(entry) => entry.isFile() && /\.mdx?$/u.test(entry.name) && !entry.name.startsWith("_"),
			)
			.map((entry) =>
				path
					.relative(repositoryRoot, path.join(entry.parentPath, entry.name))
					.split(path.sep)
					.join("/"),
			)
			.toSorted()
	);
}

if (import.meta.main) {
	const problems: string[] = [];
	let pages = 0;
	for (const guide of GUIDES) {
		for (const file of await pagesOf(`docs/${guide}`)) {
			pages += 1;
			const source = await readFile(path.resolve(repositoryRoot, file), "utf8");
			problems.push(
				...checkPage(file, guide, source),
				...screenshotProblems(
					file,
					source,
					(image) => statSync(path.resolve(repositoryRoot, image), { throwIfNoEntry: false })?.size,
				),
			);
		}
	}
	if (problems.length > 0) {
		process.stderr.write(
			`${problems.join("\n")}\nThe rules: docs/contributor/documentation-guide.md.\n`,
		);
		process.exit(1);
	}
	process.stdout.write(`${pages} docs pages follow the documentation guide.\n`);
}
