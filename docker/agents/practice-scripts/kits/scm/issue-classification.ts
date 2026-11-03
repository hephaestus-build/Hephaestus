import { optionalNumber } from "../../lib/core/json.ts";

export interface IssueMetadata {
	title?: string | null;
	body?: string | null;
	issue_type?: string | null;
	labels?: string[] | null;
	sub_issues_total?: number | null;
	sub_issues_completed?: number | null;
	sub_issues_source?: string | null;
}

/**
 * Where a captured sub-issue count came from: the provider's own rollup, or the child issues this
 * repository stored at capture. A capture that records no source carries none.
 */
export type SubIssueSource = "provider" | "children";

function subIssueSource(value: unknown): SubIssueSource | undefined {
	return value === "provider" || value === "children" ? value : undefined;
}

function norm(s: string): string {
	return s.toLowerCase().replaceAll(/[^\p{L}\p{N}]/gu, "");
}

function classifyIssue(metadata: IssueMetadata) {
	const body = (metadata.body ?? "").trim();
	const title = (metadata.title ?? "").trim();
	const issueType = (metadata.issue_type ?? "").toLowerCase();
	const labels = (metadata.labels ?? []).map((label) => label.toLowerCase());

	const emptyBody = body.length === 0;
	const titleNorm = norm(title);
	// Only a body that says the title again and nothing else repeats it; one that starts with the title
	// or mentions it carries whatever else it says.
	const titleEcho = titleNorm.length > 0 && norm(body) === titleNorm;
	const emptyOrTitleEcho = emptyBody || titleEcho;
	const deliverableType =
		/\b(?:user ?story|story|bug|defect|feature|enhancement|task|chore|requirement|sourceFile|epic|spike)\b/u;
	const hasDeliverableType =
		deliverableType.test(issueType) || labels.some((l) => deliverableType.test(l));
	const looksUmbrella =
		labels.some((l) => /\b(?:epic|umbrella|meta|initiative|requirement)\b/u.test(l)) ||
		/\b(?:epic|umbrella|initiative)\b/iu.test(title);

	return {
		body,
		title,
		issueType,
		labels,
		emptyBody,
		titleEcho,
		emptyOrTitleEcho,
		hasDeliverableType,
		looksUmbrella,
	};
}

function bodyFact({ emptyBody, titleEcho }: ReturnType<typeof classifyIssue>): string[] {
	if (emptyBody) {
		return ["The captured body is empty: the title is the only text the issue itself carries."];
	}
	return titleEcho
		? [
				"The captured body repeats the title and says nothing else (case, spacing and punctuation aside).",
			]
		: [];
}

/** What an issue's text and labels show, and the direction that its body needs, if any. */
export function issueShape(metadata: IssueMetadata) {
	const shape = classifyIssue(metadata);
	return { ...shape, bodyDirections: bodyFact(shape) };
}

/**
 * A captured sub-issue rollup and what it is a count of. A provider that does not report one leaves it
 * null or omits it, which says nothing about whether sub-issues exist: unknown stays unknown, never
 * zero. Counted from stored children, it covers only the children this repository held at capture,
 * which may omit others; all of them closed says that much and nothing about the whole hierarchy.
 */
export function subIssueRollup(metadata: IssueMetadata) {
	const total = optionalNumber(metadata.sub_issues_total);
	const completed = optionalNumber(metadata.sub_issues_completed);
	const source = subIssueSource(metadata.sub_issues_source);
	let text = "sub-issue rollup not reported by the provider (unknown, not zero)";
	if (total !== undefined) {
		text =
			completed === undefined
				? `${String(total)} sub-issue(s), completed count not reported`
				: `${String(total)} sub-issue(s), ${String(completed)} completed`;
		if (source === "provider") {
			text += ", as the provider reports them";
		} else if (source === "children") {
			text +=
				", counted from the child issues this repository stored at capture; that mirror may omit other children";
			if (total > 0 && completed === total) {
				text +=
					", so all of them closed shows only that the stored children were closed, not that the whole hierarchy is complete";
			}
		}
	}
	const metrics: Record<string, number> = {};
	if (total !== undefined) {
		metrics.subIssuesTotal = total;
	}
	if (completed !== undefined) {
		metrics.subIssuesCompleted = completed;
	}
	return { total, completed, text, metrics };
}
