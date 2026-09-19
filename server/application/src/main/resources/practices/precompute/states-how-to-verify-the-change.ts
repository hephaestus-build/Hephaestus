// Precompute FACTS for states-how-to-verify-the-change: what kind of change this is, and where
// guidance-shaped text sits. The occasion gate is where a small model slips most (a diagram or a
// self-introduction judged as lacking instructions), so the facts it needs for that gate
// are stated up front. Nothing here is a verdict — the model reads the sources and judges.
import { readContextJson } from "../lib/context.ts";
import { isJsonObject } from "../lib/practice-contract.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

const IMAGE = /\.(png|jpe?g|gif|svg|webp|pdf)$/i;
const DIAGRAM_PATH = /(^|\/)(diagrams?|uml|models?|architecture)\//i;
const DIAGRAM_JSON = /\.(json|drawio|puml|plantuml|mmd)$/i;
const PROSE = /\.(md|markdown|txt|rst|adoc)$/i;
const PROJECT_CONFIG =
	/(^|\/)(project\.yml|project\.yaml|package\.json|Package\.swift|Package\.resolved|Podfile|Cartfile|build\.gradle(\.kts)?|pom\.xml|pyproject\.toml|requirements\.txt|\.gitignore|\.editorconfig|\.swiftlint\.yml|Info\.plist)$/i;
const TEST_PATH =
	/(^|\/)(tests?|specs?|__tests__)(\/)|[._-](test|tests|spec|specs)\.[a-z]+$|Tests?\.[a-z0-9]+$|Spec\.[a-z0-9]+$/i;
const CODE = /\.(swift|ts|tsx|js|jsx|py|java|kt|go|rb|cs|cpp|cc|cxx|c|m|mm|h|hpp|vue|dart|rs)$/i;
const PREVIEW = /^\+.*(#Preview\b|PreviewProvider\b|\.stories\.[jt]sx?|storiesOf\()/;
const CLOSING = /\b(close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s*:?\s+[\w./~-]*#\d+/i;
const TESTING_HEADING =
	/^#+\s*(testing|test(ing)? instructions|how to test|verification|steps to (test|verify|reproduce))/i;
const SCREENSHOT = /!\[[^\]]*\]\([^)]*\)/g;

/** The section under a testing heading, stripped of template comments; empty when it holds nothing. */
function testingSection(body: string): { heading: string; content: string } | null {
	const lines = body.split(/\r?\n/);
	for (let index = 0; index < lines.length; index++) {
		const line = lines[index] ?? "";
		if (!TESTING_HEADING.test(line)) continue;
		const level = (/^#+/.exec(line)?.[0] ?? "#").length;
		const content: string[] = [];
		for (let next = index + 1; next < lines.length; next++) {
			const candidate = lines[next] ?? "";
			const nextLevel = /^(#+)\s/.exec(candidate)?.[1]?.length;
			if (nextLevel !== undefined && nextLevel <= level) break;
			content.push(candidate);
		}
		const text = content
			.join("\n")
			.replace(/<!--[\s\S]*?-->/g, "")
			.trim();
		return { heading: line.trim(), content: text };
	}
	return null;
}

export default async function statesHowToVerifyTheChange(
	_repoPath: string,
	diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir?: string,
) {
	const paths = [...diffFiles.keys()];
	const kinds = {
		images: paths.filter((path) => IMAGE.test(path)).length,
		diagramFiles: paths.filter(
			(path) =>
				DIAGRAM_PATH.test(path) || (DIAGRAM_JSON.test(path) && /diagram|aom|uml|model/i.test(path)),
		).length,
		prose: paths.filter((path) => PROSE.test(path)).length,
		projectConfig: paths.filter((path) => PROJECT_CONFIG.test(path)).length,
		tests: paths.filter((path) => TEST_PATH.test(path)).length,
		code: paths.filter((path) => CODE.test(path) && !TEST_PATH.test(path)).length,
	};
	const addedLines = [...diffFiles.values()].reduce((sum, file) => sum + file.addedLines.size, 0);
	const removedLines = [...diffFiles.values()].reduce(
		(sum, file) => sum + file.removedLines.size,
		0,
	);
	const previews = [...diffFiles.values()].filter((file) =>
		[...file.addedLines.values()].some((line) => PREVIEW.test(`+${line}`)),
	);

	const body = typeof metadata.body === "string" ? metadata.body : "";
	const testing = testingSection(body);
	const closing = CLOSING.exec(body)?.[0] ?? null;
	const screenshots = body.match(SCREENSHOT)?.length ?? 0;
	const linked = await readContextJson(contextDir, "linked_work_items.json");
	const linkedItems =
		isJsonObject(linked) && Array.isArray(linked.workItems) ? linked.workItems.length : 0;

	const directions: string[] = [];
	if (paths.length === 0 || addedLines + removedLines === 0) {
		directions.push(
			"The pinned range changes no lines: an empty diff is one of the kinds the criteria's Occasion section names; cite it through metadata.json changed_files or work/change/files.json.",
		);
	} else if (kinds.code === 0 && kinds.tests === 0 && kinds.projectConfig === 0) {
		directions.push(
			`No code, test or project-configuration file changes; the change is ${kinds.images} image(s), ${kinds.diagramFiles} diagram file(s) and ${kinds.prose} prose file(s). Read the prose hunks: if they add setup or run instructions the change is material; if they add an introduction, a glossary, user stories or a diagram's embedding, it is one of the kinds the criteria's Occasion section names.`,
		);
	} else {
		directions.push(
			`Material files changed: ${kinds.code} code, ${kinds.tests} test, ${kinds.projectConfig} project-configuration (plus ${kinds.prose} prose, ${kinds.images} image). The practice applies; collect guidance from every source before judging.`,
		);
	}
	if (testing) {
		directions.push(
			testing.content.length === 0
				? `The description has a testing heading ("${testing.heading}") with no content beneath it once template comments are removed: an empty heading is not evidence of anything; read the whole description, the adopted issue, previews and the documented setup.`
				: `The description has a testing section ("${testing.heading}") with ${testing.content.split(/\r?\n/).filter((line) => line.trim()).length} line(s) of content: read it in description.md and judge whether it names an entry, an action and an expected result.`,
		);
	} else {
		directions.push(
			"The description has no testing heading; guidance may still sit in any section, in an adopted issue, in a preview or test of the patch, or in the documented setup.",
		);
	}
	if (closing) {
		directions.push(
			`The description adopts an issue with a closing keyword ("${closing}"); ${linkedItems} linked issue(s) were captured as linked_work_items/<n>.md — its acceptance criteria and steps count as the author's guidance.`,
		);
	} else if (linkedItems > 0) {
		directions.push(
			`${linkedItems} linked issue(s) were captured, none by a closing keyword in the description; check whether the title or branch was created from one before treating it as adopted.`,
		);
	}
	if (previews.length > 0) {
		directions.push(
			`The patch adds an authored preview or story in: ${previews.map((file) => file.path).join(", ")} — an ENTRY for the view it renders (and, for an appearance-only change, the action).`,
		);
	}
	if (screenshots > 0) {
		directions.push(
			`The description embeds ${screenshots} image(s); read their captions and the sentences around them — the image bytes are not available to you.`,
		);
	}
	return {
		hints: [],
		metrics: {
			changedFiles: paths.length,
			addedLines,
			removedLines,
			imageFiles: kinds.images,
			diagramFiles: kinds.diagramFiles,
			proseFiles: kinds.prose,
			projectConfigFiles: kinds.projectConfig,
			testFiles: kinds.tests,
			codeFiles: kinds.code,
			previewFiles: previews.length,
			screenshotsInDescription: screenshots,
			testingSectionLines: testing
				? testing.content.split(/\r?\n/).filter((line) => line.trim()).length
				: 0,
			linkedIssues: linkedItems,
		},
		directions,
	};
}
