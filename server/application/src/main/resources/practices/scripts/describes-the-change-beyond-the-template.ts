import { definePracticeScript } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

const TEMPLATE_PLACEHOLDERS = [
	"Briefly describe what was implemented",
	"Link the related issue",
	"List steps to verify the changes",
	"State which acceptance criteria are fulfilled",
	"## Changes",
	"## Testing",
	"Add screenshots if",
	"Screenshots attached",
];
const NOT_CONTENT = [
	/^-\s*\[[ x]\]\s/iu,
	/^##\s*(?:Definition of Done|Screenshots|DoD)/iu,
	/^##\s*📌|^##\s*Title/iu,
	/^\*\*Example:?\*\*/iu,
	/^Closes?\s*#\d+/iu,
	/^Resolves?\s*#\d+/iu,
	/^Fix(?:es)?\s*#\d+/iu,
	/^origin\//u,
	/^\s*$/u,
];
const GENERIC_TITLES = new Set([
	"update",
	"fix",
	"changes",
	"small fixes",
	"further improvements",
	"improvements",
	"wip",
	"done",
]);
const VAGUE_TITLE = [
	/^update\s+(?:stuff|things|code|files?)$/iu,
	/^fix\s+(?:stuff|things|code|issues?|bugs?)$/iu,
	/^change\s+(?:stuff|things|code)$/iu,
];

const LABELS = {
	emptyBody: "The description is empty.",
	bodyRepeatsTitle: "The description's own words repeat the title.",
	checklistOnly: "The description has template checkboxes and three or fewer words of its own.",
	unfilledTemplate:
		"The description keeps two or more template placeholders and five or fewer words of its own.",
	issueReferenceOnly: "The description is only an issue reference inside template formatting.",
	pastedCommits: "Most words of the description also appear in the commit subjects.",
	genericTitle: "The title is a generic word such as update or fix.",
	briefForLargeChange:
		"The description has fewer than ten words of its own for more than five changed files.",
} as const;
type Cue = keyof typeof LABELS;

/** The description without template sections, checklists, issue references and bare headers. */
function ownContent(body: string): string {
	const kept: string[] = [];
	let inChecklist = false;
	for (const line of body.split("\n")) {
		const trimmed = line.trim();
		if (/^##\s*(?:Definition of Done|DoD|Checklist)/iu.test(trimmed)) {
			inChecklist = true;
			continue;
		}
		if (/^##\s/u.test(trimmed)) {
			inChecklist = false;
		}
		if (
			inChecklist ||
			NOT_CONTENT.some((pattern) => pattern.test(trimmed)) ||
			TEMPLATE_PLACEHOLDERS.some((placeholder) => trimmed.includes(placeholder)) ||
			/^#{1,3}\s*$/u.test(trimmed) ||
			/^##\s*(?:Description|Summary|Overview)\s*$/iu.test(trimmed) ||
			/^[-*_]{3,}\s*$/u.test(trimmed)
		) {
			continue;
		}
		kept.push(trimmed);
	}
	return kept.join(" ").replaceAll(/\s+/gu, " ").trim();
}

const normalize = (text: string) =>
	text
		.toLowerCase()
		.replaceAll(/#\d+[:\s]*/gu, "")
		.replaceAll(/[^a-z0-9\s]/gu, " ")
		.replaceAll(/\s+/gu, " ")
		.trim();
const words = (text: string) =>
	normalize(text)
		.split(/\s+/u)
		.filter((word) => word.length > 2);

/**
 * The body's own-word facts, and its cues into `cues`. A null body is a captured empty one. The
 * title, when it was not captured, is not compared.
 */
function bodyFacts(
	body: string,
	title: string | undefined,
	commits: readonly { message: string }[] | null,
	fileCount: number,
	cues: Set<Cue>,
) {
	const own = ownContent(body);
	const ownWords = own.split(/\s+/u).filter(Boolean).length;
	const checkboxes = (body.match(/-\s*\[[ x]\]/giu) ?? []).length;
	const placeholders = TEMPLATE_PLACEHOLDERS.filter((placeholder) =>
		body.includes(placeholder),
	).length;
	if (body.trim() === "") {
		cues.add("emptyBody");
	} else {
		const titleWords = words(title ?? "");
		const contentWords = words(own);
		const extra = contentWords.filter((word) => !titleWords.includes(word)).length;
		if (
			title !== undefined &&
			normalize(title).length > 3 &&
			normalize(own).length > 0 &&
			(normalize(own) === normalize(title) ||
				(titleWords.length > 0 && contentWords.length > 0 && extra === 0) ||
				(contentWords.length > 0 &&
					contentWords.length <= titleWords.length * 1.5 &&
					extra / contentWords.length < 0.2))
		) {
			cues.add("bodyRepeatsTitle");
		}
		if (ownWords <= 3 && body.length > 30 && checkboxes >= 2) {
			cues.add("checklistOnly");
		}
		const bare = body
			.replaceAll(/\s+/gu, " ")
			.trim()
			.replaceAll(/-\s*\[[ x]\][^\n]*/giu, "")
			.replaceAll(/##[^\n]*/gu, "")
			.replaceAll(/\*\*[^\n]*\*\*/gu, "")
			.replaceAll(/[#\-*>\s]/gu, "")
			.trim();
		if (/^(?:closes?|fix(?:es)?|resolve[sd]?)\s*#?\d+$/iu.test(bare)) {
			cues.add("issueReferenceOnly");
		}
		if (ownWords > 3 && commits !== null && commits.length > 0) {
			const commitWords = new Set(
				commits.flatMap((commit) => words(commit.message.split("\n")[0] ?? "")),
			);
			const mine = new Set(words(own));
			const shared = [...mine].filter((word) => commitWords.has(word)).length;
			if (mine.size > 0 && shared / mine.size >= 0.8) {
				cues.add("pastedCommits");
			}
		}
		if (
			ownWords > 0 &&
			ownWords < 10 &&
			fileCount > 5 &&
			!cues.has("bodyRepeatsTitle") &&
			!cues.has("checklistOnly")
		) {
			cues.add("briefForLargeChange");
		}
	}
	if (placeholders >= 2 && ownWords <= 5) {
		cues.add("unfilledTemplate");
	}
	return {
		descriptionWords: ownWords,
		templatePlaceholders: placeholders,
		checkboxes,
		hasScreenshot: /!\[.*\]\(.*\)|screenshot|\.png|\.jpg|\.jpeg|\.gif/iu.test(body),
	};
}

/** What the commit-subject comparison read, or why it did not run. */
function commitComparison(commits: readonly unknown[] | null, bodyCaptured: boolean): string {
	if (commits === null) {
		return "The commit range was not captured, so the description was not compared with the commit subjects.";
	}
	return bodyCaptured
		? `A description with more than three words of its own is compared with the ${String(commits.length)} captured commit subject(s).`
		: `The description was not captured, so it was not compared with the ${String(commits.length)} captured commit subject(s).`;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const { title } = work.item;
		const fileCount = work.item.changed_files;
		const commits = await work.commits();
		const cues = new Set<Cue>();
		const body =
			work.item.body === undefined
				? {}
				: bodyFacts(work.item.body ?? "", title, commits, fileCount, cues);
		const lowerTitle = title?.toLowerCase().trim();
		if (
			lowerTitle !== undefined &&
			(GENERIC_TITLES.has(lowerTitle) || VAGUE_TITLE.some((pattern) => pattern.test(lowerTitle)))
		) {
			cues.add("genericTitle");
		}
		const unknown = [
			...(work.item.body === undefined ? ["description"] : []),
			...(title === undefined ? ["title"] : []),
		];
		const directions = [
			...[...cues].map((cue) => LABELS[cue]),
			...(unknown.length === 0
				? []
				: [
						`Not captured, so unknown rather than empty: ${unknown.join(", ")}. No cue that depends on it was read.`,
					]),
			"Words of its own exclude template headings, placeholders, checkboxes and issue references. The authored practice asks the description to say what changed and why, in words a reviewer cannot get from the title, the template or the commit list.",
			commitComparison(commits, work.item.body !== undefined),
			"A small change can need only a short description. Read the description against the size and risk of the change before asking for more.",
		];
		return ctx.review({
			leads: [],
			facts: {
				...Object.fromEntries([...cues].map((cue) => [cue, true])),
				...body,
				changedFiles: fileCount,
				...(commits === null ? {} : { capturedCommits: commits.length }),
			},
			directions,
		});
	},
});
