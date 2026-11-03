import { definePracticeScript, listWithin } from "@hephaestus/practice-scripts";
import {
	branchIssueReferences,
	checkableItems,
	issueReferences,
	scm,
} from "@hephaestus/practice-scripts/scm";

const DIRECTION_CHARS = 300;

const BRANCH_CHARS = 80;

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const description = work.item.body ?? "";
		const directions: string[] = [];

		const commits = await work.commits();
		const body = `${description}\n${(commits ?? []).map((c) => c.message).join("\n")}`;
		const branch = work.item.source_branch;
		const quotedBranch = oneLine(branch, BRANCH_CHARS);
		const bodyRefs = new Set(issueReferences(body).mentioned.map((n) => `#${n}`));
		const branchRefs = new Set(branchIssueReferences(branch).map((n) => `#${n}`));
		const allRefs = new Set<string>([...bodyRefs, ...branchRefs]);
		if (allRefs.size > 0) {
			const encoded =
				branchRefs.size > 0
					? ` (branch '${quotedBranch}' encodes ${listWithin([...branchRefs], 40)})`
					: "";
			const opening = "Issue-mention syntax candidates: ";
			directions.push(
				`${opening}${listWithin([...allRefs], DIRECTION_CHARS - opening.length - encoded.length - 1)}${encoded}.`,
				"Inspect each mention in context before treating it as the author's motivating issue: templates, examples and branch numbers may be unrelated. A genuine reference need not contain a closing keyword.",
			);
		} else {
			directions.push(
				commits === null
					? `Traceability fact: no issue reference (#N, 'Refs #N', closing keyword, or issue-number branch prefix) was found in the body or branch '${quotedBranch}'. The commits were not captured, so their messages were not searched.`
					: `Traceability fact: no issue reference (#N, 'Refs #N', closing keyword, or issue-number branch prefix) was found in the body, the ${commits.length} commit message(s), or branch '${quotedBranch}'.`,
				"Confirm in the body before concluding the handoff is untraceable.",
			);
		}

		const items = checkableItems(description);
		const ticked = items.filter((item) => item.checked).length;
		const unticked = items.length - ticked;
		if (ticked + unticked > 0) {
			directions.push(
				`Checklist fact: the description carries ${ticked} ticked and ${unticked} unticked checkbox line(s). These counts say neither whose lines they are nor what was done.`,
				"Where work/change/description.authored.md exists, it separates the form's lines from the author's; otherwise compare description.md with the form.",
				"Judge whether the author adopted the checklist or wrote equivalent prose; a form's line left unticked as supplied is boilerplate, not unfinished work, and a tick is a claim, not a result.",
			);
		}

		return ctx.review({
			leads: [],
			facts: {
				issueMentionSyntaxCandidateCount: allRefs.size,
				...(commits === null ? {} : { commitCount: commits.length }),
				checklistTicked: ticked,
				checklistUnticked: unticked,
			},
			directions,
		});
	},
});

function oneLine(text: string, max: number): string {
	const flat = text.replaceAll(/\s+/gu, " ").trim();
	return flat.length <= max ? flat : `${flat.slice(0, max - 1)}…`;
}
