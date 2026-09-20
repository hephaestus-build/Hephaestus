// Precompute FACTS for links-the-change-to-its-issue: where this pull request names an issue — title,
// body, source branch, commit subjects, linked work items — and, when it names none, whether the
// project tracks issues at all. The review decides whether a reference is a link; the script says
// where each candidate was found.
import { readContextJson, readProjectInventory } from "../lib/context.ts";
import { isJsonObject } from "../lib/practice-contract.ts";
import {
	branchIssueReferences,
	closingReferences,
	issueNumberReferences,
} from "../lib/references.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

function text(value: unknown): string {
	return typeof value === "string" ? value : "";
}

function linkedItemCount(data: unknown): number | null {
	if (Array.isArray(data)) {
		return data.length;
	}
	if (!isJsonObject(data)) {
		return null;
	}
	if (Array.isArray(data.workItems)) {
		return data.workItems.length;
	}
	if (Array.isArray(data.items)) {
		return data.items.length;
	}
	return null;
}

export default async function linksTheChangeToItsIssue(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir?: string,
) {
	const hints: Hint[] = [];
	const add = (where: string, numbers: number[], closing: boolean) => {
		for (const n of numbers) {
			hints.push({
				file: where,
				line: 0,
				pattern: closing ? "closing reference" : "issue reference",
				context: `#${n}`,
				inDiff: false,
				flags: { number: n, where },
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
	const linked = linkedItemCount(await readContextJson(contextDir, "linked_work_items.json"));
	const inventory = await readProjectInventory(contextDir);
	const numbers = new Set(hints.map((h) => h.flags.number));
	const metrics = {
		referencesFound: numbers.size,
		closingReferences: hints.filter((h) => h.pattern === "closing reference").length,
		linkedWorkItems: linked ?? -1,
		inventoryIssues: inventory?.issues?.length ?? -1,
		inventoryOpenIssues:
			inventory?.issues?.filter((i) => i.state === "open" || i.state === "opened").length ?? -1,
	};
	const directions: string[] = [];
	if (numbers.size > 0) {
		directions.push(
			`Issue-shaped references found: ${[...numbers].map((n) => `#${String(n)}`).join(", ")} — confirm each names an issue of this project in the inventory or the linked items.`,
		);
	} else {
		directions.push(
			`No #number, closing keyword or branch number in the title, body or branch (${branch}); ${linked === null ? "linked_work_items.json was not captured" : `${String(linked)} linked work item(s) captured`}. Read work/change/commits.json for a reference in history, then the inventory (${metrics.inventoryIssues < 0 ? "not captured" : `${String(metrics.inventoryIssues)} issues`}) for an open issue that plainly describes this work.`,
		);
	}
	return { hints: hints.slice(0, 20), metrics, directions };
}
