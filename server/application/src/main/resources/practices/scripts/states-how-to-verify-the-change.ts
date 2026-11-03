import { definePracticeScript, listWithin, type ReviewLead } from "@hephaestus/practice-scripts";
import {
	branchIssueReferences,
	issueReferences,
	scm,
	type DiffFile,
} from "@hephaestus/practice-scripts/scm";

const DIRECTION_CHARS = 300;

export const LABELS = {
	"xcode-placeholder": "An added line that keeps an Xcode placeholder token.",
	"runtime-secret-read": "An added line that reads a key or secret at runtime.",
	"ignored-secret":
		"A line that the change adds to an ignore file and that names a secret or a configuration file.",
};

const IMAGE = /\.(?:png|jpe?g|gif|svg|webp|pdf)$/iu;
const DIAGRAM_PATH = /(?:^|\/)(?:diagrams?|uml|models?|architecture)\//iu;
const DIAGRAM_JSON = /\.(?:json|drawio|puml|plantuml|mmd)$/iu;
const PROSE = /\.(?:md|markdown|txt|rst|adoc)$/iu;
const PROJECT_CONFIG =
	/(?:^|\/)(?:project\.yml|project\.yaml|package\.json|Package\.swift|Package\.resolved|Podfile|Cartfile|build\.gradle(?:\.kts)?|pom\.xml|pyproject\.toml|requirements\.txt|\.gitignore|\.editorconfig|\.swiftlint\.yml|Info\.plist)$/iu;
const TEST_PATH =
	/(?:^|\/)(?:tests?|specs?|__tests__)(?:\/)|[._-](?:test|tests|spec|specs)\.[a-z]+$|Tests?\.[a-z0-9]+$|Spec\.[a-z0-9]+$/iu;
const CODE = /\.(?:swift|ts|tsx|js|jsx|py|java|kt|go|rb|cs|cpp|cc|cxx|c|m|mm|h|hpp|vue|dart|rs)$/iu;
const PREVIEW = /^\+.*(?:#Preview\b|PreviewProvider\b|\.stories\.[jt]sx?|storiesOf\()/u;
// CommonMark permits up to three spaces before a heading.
const TESTING_HEADING =
	/^ {0,3}#+\s*(?:testing|test(?:ing)? instructions|how to test|verification|steps to (?:test|verify|reproduce))/iu;
const PLACEHOLDER_TESTING_TEXT = /^(?:n\/?a|none|not applicable|-|—)\.?$/iu;
const SCREENSHOT = /!\[[^\]]*\]\([^)]*\)/gu;
const PLACEHOLDER_TOKEN = /\/\*@(?:START_MENU_TOKEN|PLACEHOLDER=)/u;
const SECRET_NAME =
	/api[_-]?key|apikey|client[_-]?secret|\bsecrets?\b|secret[A-Z_]|access[_-]?token/iu;
const RUNTIME_LOOKUP =
	/Bundle\.main|ProcessInfo|\benvironment\[|\.plist|xcconfig|Keychain|SecItem|UserDefaults|infoDictionary|process\.env|System\.getenv|os\.environ/u;

function testingSection(body: string): { heading: string; content: string } | null {
	const lines = body.split(/\r?\n/u);
	for (let index = 0; index < lines.length; index += 1) {
		const line = lines[index] ?? "";
		if (!TESTING_HEADING.test(line)) {
			continue;
		}
		const level = (/#+/u.exec(line)?.[0] ?? "#").length;
		const content: string[] = [];
		for (let next = index + 1; next < lines.length; next += 1) {
			const candidate = lines[next] ?? "";
			const nextLevel = /^ {0,3}(?<hashes>#+)\s/u.exec(candidate)?.groups?.hashes?.length;
			if (nextLevel !== undefined && nextLevel <= level) {
				break;
			}
			content.push(candidate);
		}
		const text = withoutHtmlComments(content.join("\n")).trim();
		return { heading: line.trim(), content: text };
	}
	return null;
}

const HEADING_CHARS = 100;

function addedLinesMatching(
	change: ReadonlyMap<string, DiffFile>,
	kind: keyof typeof LABELS,
	matches: (path: string, line: string) => boolean,
): ReviewLead[] {
	return [...change.values()].flatMap((file) =>
		[...file.addedLines]
			.filter(([, line]) => matches(file.path, line))
			.map(([line]): ReviewLead => ({ at: { change: file.path, line }, label: LABELS[kind] })),
	);
}

function listed(opening: string, items: readonly string[], separator = ", "): string {
	return `${opening}${listWithin(items, DIRECTION_CHARS - opening.length - 1, separator)}.`;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const { item: record } = work;
		const change = work.changedFiles();
		const paths = [...change.keys()];
		const unchanged = [...change.values()]
			.filter(
				(file) => file.hunks.length === 0 && file.addedLines.size + file.removedLines.size === 0,
			)
			.map((file) => file.path)
			.filter((path) => !IMAGE.test(path));
		const content = paths.filter((path) => !unchanged.includes(path));
		const kinds = {
			images: content.filter((path) => IMAGE.test(path)).length,
			diagramFiles: content.filter(
				(path) =>
					DIAGRAM_PATH.test(path) ||
					(DIAGRAM_JSON.test(path) && /diagram|aom|uml|model/iu.test(path)),
			).length,
			prose: content.filter((path) => PROSE.test(path)).length,
			projectConfig: content.filter((path) => PROJECT_CONFIG.test(path)).length,
			tests: content.filter((path) => TEST_PATH.test(path)).length,
			code: content.filter((path) => CODE.test(path) && !TEST_PATH.test(path)).length,
		};
		const addedLines = [...change.values()].reduce((sum, file) => sum + file.addedLines.size, 0);
		const removedLines = [...change.values()].reduce(
			(sum, file) => sum + file.removedLines.size,
			0,
		);
		const previews = [...change.values()].filter((file) =>
			[...file.addedLines.values()].some((line) => PREVIEW.test(`+${line}`)),
		);
		const placeholders = addedLinesMatching(change, "xcode-placeholder", (_path, line) =>
			PLACEHOLDER_TOKEN.test(line),
		);
		const secretReads = addedLinesMatching(
			change,
			"runtime-secret-read",
			(path, line) =>
				!path.endsWith(".gitignore") && SECRET_NAME.test(line) && RUNTIME_LOOKUP.test(line),
		);
		const ignoredSecrets = addedLinesMatching(
			change,
			"ignored-secret",
			(path, line) =>
				path.endsWith(".gitignore") &&
				(SECRET_NAME.test(line.trim()) || /\.xcconfig$|\.env\b/iu.test(line.trim())),
		);

		const body = record.body ?? "";
		const testing = testingSection(body);
		const screenshots = body.match(SCREENSHOT)?.length ?? 0;
		const capture = await work.linkedIssues();
		const captured = capture?.items.map((item) => item.number) ?? [];
		const named = namedIssues([
			[
				"provider closing link",
				(capture?.items ?? [])
					.filter((item) => item.how === "closesOnMerge")
					.map((item) => item.number),
			],
			["closing keyword in the description", issueReferences(body).closing],
			["title", issueReferences(record.title ?? "").mentioned],
			["branch", branchIssueReferences(record.source_branch)],
		]);

		const directions: string[] = [];
		if (paths.length === 0 || addedLines + removedLines === 0) {
			directions.push(
				"The pinned range changes no lines: an empty diff is one of the kinds the criteria's applicability section names; cite it through metadata.json changed_files or work/change/files.json.",
			);
		} else if (kinds.code === 0 && kinds.tests === 0 && kinds.projectConfig === 0) {
			directions.push(
				`No code, test or project-configuration file has line changes; the change is ${kinds.images} image(s), ${kinds.diagramFiles} diagram file(s) and ${kinds.prose} prose file(s).`,
				"Read the prose hunks: if they add setup or run instructions the change is material; if they add an introduction, a glossary, user stories or a diagram's embedding, it is one of the kinds the criteria's applicability section names.",
			);
		} else {
			directions.push(
				`Files with line changes: ${kinds.code} code, ${kinds.tests} test, ${kinds.projectConfig} project-configuration, ${kinds.prose} prose, ${kinds.images} image.`,
			);
		}
		if (unchanged.length > 0) {
			directions.push(
				listed(
					`${unchanged.length} file(s) change no line — moves, renames or binary files: `,
					unchanged,
				),
			);
		}
		const elsewhere =
			" That settles nothing either way: the entry, action and expected result can be in other sections, an adopted issue, a preview, the documented setup or the author's discussion.";
		const heading = testing === null ? "" : oneLine(testing.heading, HEADING_CHARS);
		if (testing === null) {
			directions.push(`The description has no testing heading.${elsewhere}`);
		} else if (testing.content.length === 0) {
			directions.push(
				`The description's testing heading ("${heading}") has no content beneath it once template comments are removed.`,
				elsewhere.trim(),
			);
		} else if (PLACEHOLDER_TESTING_TEXT.test(testing.content)) {
			directions.push(
				`The description's testing section ("${heading}") holds only "${testing.content}".`,
				elsewhere.trim(),
			);
		} else {
			directions.push(
				`The description's testing section ("${heading}") holds ${testing.content.split(/\r?\n/u).filter((line) => line.trim()).length} line(s) of author text.`,
			);
		}
		if (named.size > 0) {
			directions.push(
				listed(
					"Issue(s) the author names: ",
					[...named].map(([n, how]) => `#${n} (${how.join(", ")})`),
					"; ",
				),
			);
		}
		const unnamed = captured.filter((n) => !named.has(n));
		if (unnamed.length > 0) {
			directions.push(
				listed(
					"Captured as linked_work_items/<n>.md but not named by a closing keyword, the title, the branch or a provider closing link: ",
					unnamed.map((n) => `#${n}`),
				),
			);
		}
		const uncaptured = [...named.keys()].filter((n) => !captured.includes(n));
		if (capture !== null && uncaptured.length > 0) {
			directions.push(
				listed(
					"Named by the author but not captured: ",
					uncaptured.map((n) => `#${n}`),
				),
			);
		}
		if (previews.length > 0) {
			directions.push(
				listed(
					"The patch adds an authored preview or story in: ",
					previews.map((file) => file.path),
				),
			);
		}
		if (screenshots > 0) {
			directions.push(
				`The description embeds ${screenshots} image(s); read their captions and the sentences around them — the image bytes are not available to you.`,
			);
		}
		return ctx.review({
			leads: [...placeholders, ...secretReads, ...ignoredSecrets].slice(0, 40),
			facts: {
				changedFiles: paths.length,
				filesWithoutLineChanges: unchanged.length,
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
					? testing.content.split(/\r?\n/u).filter((line) => line.trim()).length
					: 0,
				linkedIssues: captured.length,
				namedIssues: named.size,
				secretReadLines: secretReads.length,
			},
			directions,
		});
	},
});

function namedIssues(
	ways: readonly (readonly [how: string, issues: readonly number[]])[],
): Map<number, string[]> {
	const named = new Map<number, string[]>();
	for (const [how, issues] of ways) {
		for (const number of issues) {
			named.set(number, [...(named.get(number) ?? []), how]);
		}
	}
	return named;
}

// Providers do not link issue references inside hidden HTML comments.
const HTML_COMMENT = /<!--[\s\S]*?-->/gu;

// Retain a boundary so removed comments cannot join separate tokens.
function withoutHtmlComments(text: string): string {
	let result = text;
	let previous;
	do {
		previous = result;
		result = result.replaceAll(HTML_COMMENT, " ");
	} while (result !== previous);
	return result;
}

function oneLine(text: string, max: number): string {
	const flat = text.replaceAll(/\s+/gu, " ").trim();
	return flat.length <= max ? flat : `${flat.slice(0, max - 1)}…`;
}
