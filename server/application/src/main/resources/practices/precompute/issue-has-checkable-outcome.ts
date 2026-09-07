import { classifyIssue, type IssueMetadata } from "../lib/issue-classification.ts";
import type { Hint } from "../lib/types.ts";

export default function issueHasCheckableOutcome(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMetadata,
) {
	const { body, emptyOrTitleEcho, hasDeliverableType, looksUmbrella } = classifyIssue(m);
	const uncheckedBoxes = (body.match(/^[\s>]*[-*]\s+\[ \]/gm) ?? []).length;
	const checkedBoxes = (body.match(/^[\s>]*[-*]\s+\[[xX]\]/gm) ?? []).length;
	const totalBoxes = uncheckedBoxes + checkedBoxes;

	const acHeading =
		/(acceptance criteria|definition of done|\bDoD\b|done when|verif(y|iable)|expected (outcome|result|behaviou?r))/i.test(
			body,
		);
	const valueClause =
		/\bso that\b/i.test(body) || /\bas an?\b[\s\S]{0,60}\bi (want|need|would like)\b/i.test(body);
	const isStub = body.length < 40;

	const directions: string[] = [];
	if (emptyOrTitleEcho && hasDeliverableType) {
		directions.push(
			`Classification fact: the body is empty or echoes the title yet carries a deliverable type — there is no checkable outcome and no actionable content for a reader to verify "done" against.`,
		);
	} else if (looksUmbrella) {
		directions.push(
			`Classification fact: umbrella/requirement card — its verifiable outcome is normally a decomposition into child stories that each carry their own acceptance criteria, rather than an inline checkbox block.`,
		);
	}
	if (isStub && !emptyOrTitleEcho)
		directions.push(
			`Body is ${body.length} chars — thin; do not credit a verifiable "done" without quotable text.`,
		);
	directions.push(
		`Checkable-outcome facts: acceptanceCriteriaHeadingPresent=${acHeading}, taskCheckboxes=${totalBoxes} (unchecked=${uncheckedBoxes}, checked=${checkedBoxes}), valueClausePresent=${valueClause}.`,
	);
	if (totalBoxes > 0)
		directions.push(
			`A task checklist exists — judge whether the boxes are concrete verifiable outcomes (not vague intentions); an explicit acceptance-criteria block is a stronger verifiable-done signal than a bare value clause.`,
		);
	if (totalBoxes === 0 && !acHeading)
		directions.push(
			`No checklist and no acceptance-criteria heading detected — confirm there is genuinely no quotable verifiable-done artifact before crediting one.`,
		);

	const hints: Hint[] = [];
	return {
		hints,
		metrics: {
			bodyLength: body.length,
			acceptanceCriteriaHeadingPresent: acHeading ? 1 : 0,
			taskCheckboxes: totalBoxes,
			uncheckedBoxes,
			checkedBoxes,
			valueClausePresent: valueClause ? 1 : 0,
			emptyOrTitleEcho: emptyOrTitleEcho ? 1 : 0,
			hasDeliverableType: hasDeliverableType ? 1 : 0,
			looksUmbrella: looksUmbrella ? 1 : 0,
		},
		directions,
	};
}
