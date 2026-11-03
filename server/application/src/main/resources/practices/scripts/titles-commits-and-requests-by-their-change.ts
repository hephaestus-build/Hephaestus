import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

const MEANINGLESS = [
	/^\.+$/u,
	/^-+$/u,
	/^[a-f0-9]{6,}$/iu,
	/^(?:wip|test|commit|push|save|done|finished|final|temp|tmp|xxx|aaa|todo)$/iu,
	/^asdf/iu,
	/^!!+$/u,
	/^\?+$/u,
];
const BARE_VERBS = new Set(
	"fix fixes fixed update updates updated change changes changed stuff cleanup refactor tweaks tweak adjust minor patch improve improvements edit edits".split(
		" ",
	),
);
const VAGUE_VERB_OBJECT = [
	/^fix\s+(?:stuff|things|it|this|that|code|bug|bugs|issue|issues|error|errors)$/iu,
	/^update\s+(?:stuff|things|it|this|that|code|file|files)$/iu,
	/^change\s+(?:stuff|things|it|this|that|code)$/iu,
	/^(?:small|minor|some)\s+(?:fix|fixes|changes|update|updates|tweaks?)$/iu,
	/^(?:further|more)\s+(?:fix|fixes|changes|improvements?)$/iu,
	/^apply\s+(?:feedback|review|suggestions?|changes?)$/iu,
	/^address\s+(?:feedback|review|comments?)$/iu,
	/^review\s*(?:feedback|changes?)?$/iu,
	/^ai\s+review$/iu,
	/^code\s+(?:cleanup|review)$/iu,
];
const MERGE = /^Merge\s+(?:branch|remote|pull|request)/iu;

/** The authored program's category for a commit subject, or null when none applies. */
function subjectCategory(subject: string): string | null {
	const lower = subject.toLowerCase();
	if (subject.length < 3 || MEANINGLESS.some((pattern) => pattern.test(lower))) {
		return "meaningless";
	}
	if (BARE_VERBS.has(lower)) {
		return "bareVerb";
	}
	if (VAGUE_VERB_OBJECT.some((pattern) => pattern.test(lower))) {
		return "vagueObject";
	}
	if (/^fix\s+(?:swiftlint|lint|linting|pipeline|ci|formatting|format)$/iu.test(lower)) {
		return "toolOnly";
	}
	if (/^\d+[-/]\d+[-/]\d+/u.test(subject) || /^[a-z]+-[a-z]+-[a-z]+(?:-[a-z]+)*$/iu.test(lower)) {
		return "branchSlug";
	}
	return null;
}

/** The title cues of the authored program, as neutral statements. */
function titleCuesOf(title: string, sourceBranch: string): string[] {
	const cues: string[] = [];
	const lowerTitle = title.toLowerCase().trim();
	const meaningless =
		title.trim().length < 3 || MEANINGLESS.some((pattern) => pattern.test(lowerTitle));
	if (meaningless) {
		cues.push("The title is shorter than three characters or a placeholder word.");
	}
	if (/^(?:resolve|closes?|fix(?:es)?)\s*#\d+$/iu.test(lowerTitle)) {
		cues.push("The title is only an issue reference.");
	}
	if (
		!meaningless &&
		(/^(?:fix|update|change|add|remove|delete|refactor|clean|improve|adjust)\s*$/iu.test(
			lowerTitle,
		) ||
			VAGUE_VERB_OBJECT.some((pattern) => pattern.test(lowerTitle)))
	) {
		cues.push("The title is a verb with no subject or a generic object.");
	}
	const branch = sourceBranch
		.replaceAll("-", " ")
		.replace(/^(?:feature|fix|feat|bugfix)\/?/iu, "")
		.trim()
		.toLowerCase();
	const titleText = title
		.replace(/^#?\d+[-/:]\s*/u, "")
		.replaceAll("-", " ")
		.trim()
		.toLowerCase();
	if (branch.length > 3 && titleText.length > 3 && branch === titleText) {
		cues.push("The title is the source branch name reformatted.");
	}
	return cues;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const { title } = work.item;
		const leads: ReviewLead[] = [];
		const titleCues = title === undefined ? [] : titleCuesOf(title, work.item.source_branch);
		const commits = await work.commits();
		const authored = (commits ?? [])
			.map((commit) => ({ commit, subject: (commit.message.split("\n")[0] ?? "").trim() }))
			.filter(({ subject }) => subject.length > 0 && !MERGE.test(subject));
		const categories = new Map<string, number>();
		for (const { commit, subject } of authored) {
			const category = subjectCategory(subject);
			if (category !== null) {
				categories.set(category, (categories.get(category) ?? 0) + 1);
				leads.push({
					at: { record: "commits.json", line: commit.line },
					label: "An authored commit whose subject does not say what changed.",
					facts: { sha: commit.sha.slice(0, 7), category },
				});
			}
		}
		const subjects = new Map<string, number>();
		for (const { subject } of authored) {
			subjects.set(subject.toLowerCase(), (subjects.get(subject.toLowerCase()) ?? 0) + 1);
		}
		const repeatedSubjects = [...subjects.values()].filter((count) => count > 1).length;
		const vague = [...categories.values()].reduce((sum, count) => sum + count, 0);
		const directories = new Set(
			[...(work.change?.files.keys() ?? [])].flatMap((path) =>
				path.includes("/") ? [path.slice(0, path.lastIndexOf("/"))] : [],
			),
		);
		const directions = [
			...titleCues,
			...(title === undefined
				? ["The title was not captured, so it is unknown, not empty: no title cue was read."]
				: []),
			"The authored practice asks that a title and each commit subject say what changed, so a reader can follow the history without opening the diff. Merge commits are not read.",
			commits === null
				? "The commit range was not captured; its subjects are unknown, not clean."
				: `${String(vague)} of ${String(authored.length)} authored commit subject(s) match the authored program's vague patterns, and ${String(repeatedSubjects)} subject(s) repeat.`,
		];
		if (authored.length >= 3 && vague / authored.length >= 0.5) {
			directions.push(
				"Half or more of the authored commits have vague subjects. Read whether this is a pattern for the change as a whole, not one slip.",
			);
		}
		if (work.item.changed_files >= 20 && directories.size >= 4) {
			directions.push(
				`The change touches ${String(work.item.changed_files)} files in ${String(directories.size)} directories. Read whether one title can describe it or whether it holds separate changes.`,
			);
		}
		return ctx.review({
			leads: leads.slice(0, 60),
			facts: {
				...(title === undefined ? {} : { titleLength: title.length, titleCues: titleCues.length }),
				changedFiles: work.item.changed_files,
				...(commits === null
					? {}
					: { authoredCommits: authored.length, vagueSubjects: vague, repeatedSubjects }),
			},
			directions,
		});
	},
});
