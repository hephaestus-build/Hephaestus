import { definePracticeScript } from "@hephaestus/practice-scripts";
import { checkableItems, scm, subIssueRollup } from "@hephaestus/practice-scripts/scm";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.issue");
		const items = checkableItems(work.item.body);
		const checked = items.filter((item) => item.checked).length;
		const rollup = subIssueRollup(work.item);
		return ctx.review({
			leads: [],
			facts: {
				...(work.item.state === "UNKNOWN"
					? {}
					: { issueClosed: work.item.state === "CLOSED" ? 1 : 0 }),
				checkedBoxes: checked,
				uncheckedBoxes: items.length - checked,
				...rollup.metrics,
			},
			directions: [
				`Captured issue state: ${work.item.state}; reason: ${work.item.state_reason ?? "none recorded"}; closed at: ${work.item.closed_at ?? "unknown"}.`,
				`The captured body has ${String(checked)} ticked and ${String(items.length - checked)} unticked task-list item(s).`,
				`Sub-issues: ${rollup.text}.`,
				"These values describe the capture, not necessarily the moment of closing. Compare the dated closing and comment evidence before saying an outcome remained open when the issue closed; leave that historical question unresolved when the evidence is absent.",
				"Judge the authored standard once. Read the closure disposition and any explanation or follow-up for the remainder; an unchecked line or an open stored child alone proves neither unfinished work nor an improper close.",
			],
		});
	},
});
