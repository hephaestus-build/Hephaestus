// Precompute FACTS for links-the-change-to-its-issue: where this pull request names an issue — title,
// body, source branch, commit subjects, linked work items — each resolved against the project
// inventory and the linked items, and, when it names none, whether the project tracks issues at all.
// The review decides whether a reference is a link; the script says where each candidate was found
// and what the inventory holds under that number.
import { inventoryIssues, isOpenState, readProjectInventory } from "../lib/context.ts";
import { text } from "../lib/practice-contract.ts";
import {
	branchIssueReferences,
	closingReferences,
	issueNumberReferences,
} from "../lib/references.ts";
import { readLinkedWorkItems } from "../lib/review.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

export default async function linksTheChangeToItsIssue(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir?: string,
) {
	const linked = await readLinkedWorkItems(contextDir);
	const linkedNumbers = new Set((linked ?? []).map((item) => item.number));
	const inventory = await readProjectInventory(contextDir);
	const issues = inventoryIssues(inventory);
	const hints: Hint[] = [];
	const add = (where: string, numbers: number[], closing: boolean) => {
		for (const n of numbers) {
			const issue = issues.get(n);
			hints.push({
				file: where,
				line: 0,
				pattern: closing ? "closing reference" : "issue reference",
				context: `#${n}${issue === undefined ? "" : ` ${issue.title}`}`,
				inDiff: false,
				flags: {
					number: n,
					where,
					inInventory: issue !== undefined,
					title: issue?.title ?? "",
					state: issue?.state ?? "",
					inLinkedItems: linkedNumbers.has(n),
				},
			});
		}
	};
	const title = text(metadata.title);
	const body = text(metadata.body);
	const branch = metadata.source_branch;
	add("inputs/context/metadata.json (title)", issueNumberReferences(title), false);
	add("inputs/context/description.md", closingReferences(body), true);
	add(
		"inputs/context/description.md",
		issueNumberReferences(body).filter((n) => !closingReferences(body).includes(n)),
		false,
	);
	add("inputs/context/metadata.json (source_branch)", branchIssueReferences(branch), false);
	const numbers = new Set(hints.map((h) => h.flags.number));
	const metrics = {
		referencesFound: numbers.size,
		referencesInInventory: new Set(
			hints.filter((h) => h.flags.inInventory === true).map((h) => h.flags.number),
		).size,
		closingReferences: hints.filter((h) => h.pattern === "closing reference").length,
		linkedWorkItems: linked?.length ?? -1,
		inventoryIssues: inventory?.issues?.length ?? -1,
		inventoryOpenIssues: inventory?.issues?.filter((i) => isOpenState(i.state)).length ?? -1,
	};
	const directions: string[] = [];
	if (numbers.size > 0) {
		directions.push(
			`Issue-shaped references found: ${[...numbers].map((n) => `#${String(n)}`).join(", ")}, one row each with what the inventory holds under that number (${inventory === null ? "no inventory captured" : `${String(metrics.inventoryIssues)} issues`}) and whether it is among the linked items (${linked === null ? "linked_work_items.json not captured" : `${String(linked.length)} captured`}).`,
		);
	} else {
		directions.push(
			`No #number, closing keyword or branch number in the title, body or branch (${branch}); ${linked === null ? "linked_work_items.json was not captured" : `${String(linked.length)} linked work item(s) captured`}. Read inputs/context/commits.json for a reference in history, then the inventory (${inventory === null ? "not captured" : `${String(metrics.inventoryIssues)} issues, ${String(metrics.inventoryOpenIssues)} open`}) for an open issue that plainly describes this work.`,
		);
	}
	return { hints: hints.slice(0, 20), metrics, directions };
}
