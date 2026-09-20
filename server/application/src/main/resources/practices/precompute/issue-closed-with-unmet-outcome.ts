// Current captured issue facts; closure timestamps do not date mutable body or sub-issue state.
import type { Hint } from "../lib/types.ts";

interface IssueMeta {
	body?: string;
	state?: string;
	state_reason?: string | null;
	sub_issues_total?: number;
	sub_issues_completed?: number;
	closed_at?: string | null;
}

export default function issueClosedWithUnmetOutcome(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMeta,
) {
	const body = (m.body ?? "").trim();
	const state = (m.state ?? "").toUpperCase();
	const reason = m.state_reason ?? null;
	const unchecked = (body.match(/^[\s>]*[-*]\s+\[ \]/gmu) ?? []).length;
	const checked = (body.match(/^[\s>]*[-*]\s+\[[xX]\]/gmu) ?? []).length;
	const subTotal = m.sub_issues_total ?? 0;
	const subDone = m.sub_issues_completed ?? 0;
	const subOpen = Math.max(0, subTotal - subDone);

	const directions: string[] = [];
	if (state === "CLOSED") {
		directions.push(
			`Current captured issue facts: state_reason=${reason ?? "none"}, uncheckedBoxes=${unchecked}, checkedBoxes=${checked}, subIssuesOpen=${subOpen}/${subTotal}, closed_at=${m.closed_at ?? "?"}.`,
		);
		if (unchecked > 0 || subOpen > 0) {
			directions.push(
				`The current record has ${unchecked} unchecked item(s) and ${subOpen} open sub-issue(s). Establish their state at the relevant closure from dated evidence before judging a closure decision. A current unticked box is neither proof of unfinished work nor a developer-wide habit.`,
			);
		} else if (subTotal > 0 || checked > 0) {
			directions.push(
				`The current record has ${checked} checked item(s) and ${subTotal} completed sub-issue(s). This does not establish their state at closure or prove the work was verified; inspect dated closure evidence before assessing that event.`,
			);
		}
	} else {
		directions.push(
			`Issue state is ${state || "unknown"} — not CLOSED; this close-time check concerns only closed issues.`,
		);
	}

	const hints: Hint[] = [];
	return {
		hints,
		metrics: {
			stateClosed: state === "CLOSED" ? 1 : 0,
			uncheckedBoxes: unchecked,
			checkedBoxes: checked,
			currentSubIssuesOpen: subOpen,
			subIssuesTotal: subTotal,
		},
		directions,
	};
}
