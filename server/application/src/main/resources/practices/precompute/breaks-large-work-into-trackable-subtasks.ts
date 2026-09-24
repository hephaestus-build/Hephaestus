// Precompute HINTS for breaks-large-work-into-trackable-subtasks: surface (a) whether the issue is
// LEGITIMATELY large/multi-part and (b) whether it already carries an explicit breakdown (task checklist,
// sub-issues, referenced child issues). FACTS only — the LLM decides whether large work is adequately
// decomposed. No observation.
import type { Hint } from "../lib/types.ts";

interface IssueMeta {
	title?: string;
	body?: string;
	labels?: string[];
	sub_issues_total?: number;
	sub_issues_completed?: number;
}

export default function breaksLargeWorkIntoTrackableSubtasks(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMeta,
) {
	const body = (m.body ?? "").trim();
	const labels = (m.labels ?? []).map((l) => l.toLowerCase());

	const checkboxes = (body.match(/^[\s>]*[-*]\s+\[[ xX]\]/gmu) ?? []).length;
	const childRefs = new Set((body.match(/(?:^|\s)#\d+\b/gu) ?? []).map((s) => s.trim())).size;
	const subTotal = m.sub_issues_total ?? 0;
	const subDone = m.sub_issues_completed ?? 0;
	const headingSections = (body.match(/^#{1,4}\s+\S/gmu) ?? []).length;
	const isEpic = labels.some((l) => /epic|meta|tracking|umbrella/u.test(l));
	const bigBody = body.length > 1200;

	const hasBreakdown = checkboxes > 0 || childRefs > 0 || subTotal > 0;
	const looksLarge = isEpic || bigBody || headingSections >= 4 || childRefs >= 2;

	const directions: string[] = [
		`Largeness signals: epicLabel=${isEpic}, bodyChars=${body.length}, headingSections=${headingSections}, childIssueRefs=${childRefs}. This practice only applies to LEGITIMATELY large/multi-part work — confirm largeness before judging decomposition.`,
		`Breakdown facts: taskCheckboxes=${checkboxes}, childIssueRefs=${childRefs}, subIssuesTotal=${subTotal} (completed=${subDone}). hasAnyBreakdown=${hasBreakdown}.`,
	];
	if (looksLarge && !hasBreakdown) {
		directions.push(
			`Signals suggest large work with NO explicit breakdown — a strong candidate for a decomposition finding; verify the body really bundles multiple trackable parts.`,
		);
	}
	if (!looksLarge) {
		directions.push(
			`Does not look large/multi-part (no epic label, small body, few heading sections and child refs) — a single small ask carries little large-work-decomposition surface.`,
		);
	}

	const hints: Hint[] = [];
	return {
		hints,
		metrics: {
			bodyLength: body.length,
			epicLabel: isEpic ? 1 : 0,
			headingSections,
			taskCheckboxes: checkboxes,
			childIssueRefs: childRefs,
			subIssuesTotal: subTotal,
			subIssuesCompleted: subDone,
			hasAnyBreakdown: hasBreakdown ? 1 : 0,
			looksLarge: looksLarge ? 1 : 0,
		},
		directions,
	};
}
