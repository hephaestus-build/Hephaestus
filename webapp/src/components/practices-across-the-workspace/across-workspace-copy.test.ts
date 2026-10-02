import { describe, expect, it } from "vitest";

import type { WorkspaceGroupSplit } from "@/api/types.gen";

import { estimateSentence, orderGroups, reachSentence } from "./across-workspace-copy";

const group = (overrides: Partial<WorkspaceGroupSplit>): WorkspaceGroupSplit => ({
	groupSlug: "g",
	groupName: "G",
	yourStanding: "MIXED",
	shape: "SPLIT",
	needsAttention: 5,
	mixedFeedback: 6,
	goingWell: 7,
	...overrides,
});

describe("reachSentence", () => {
	it("names the reference group and calls the group within reach where most are going well", () => {
		expect(reachSentence(group({}), "TERM")).toBe(
			"7 of the 18 developers observed here this term are Going well, so it is within reach.",
		);
	});

	it("says the group is hard where most get mixed feedback", () => {
		expect(reachSentence(group({ mixedFeedback: 9 }), "DAYS_30")).toBe(
			"9 of the 21 developers observed here in the last 30 days get Mixed feedback; many find this group hard.",
		);
	});

	it("says the split is held back when it collapsed, and nothing when it was withheld", () => {
		expect(reachSentence(group({ shape: "COLLAPSED", hasStanding: 12, noneYet: 6 }), "TERM")).toBe(
			"12 of the 18 developers observed here this term have a standing; the split is held back.",
		);
		expect(reachSentence(group({ shape: "WITHHELD" }), "TERM")).toBeUndefined();
	});
});

describe("estimateSentence", () => {
	it("sets the estimate beside the standing without judging either", () => {
		expect(estimateSentence("STRENGTH", "DEVELOPING")).toBe(
			"You expected Going well; your latest reviewed work reads Needs attention.",
		);
		expect(estimateSentence("MIXED", "MIXED")).toBe(
			"You expected Mixed feedback; your latest reviewed work reads Mixed feedback too.",
		);
		expect(estimateSentence("SKIPPED", "STRENGTH")).toBe(
			"You skipped the estimate; your latest reviewed work reads Going well.",
		);
	});
});

describe("orderGroups", () => {
	const groups = [
		group({ groupSlug: "b", groupName: "Beta", yourStanding: "STRENGTH" }),
		group({ groupSlug: "a", groupName: "Alpha", yourStanding: "NOT_OBSERVED" }),
		group({ groupSlug: "c", groupName: "Gamma", yourStanding: "DEVELOPING" }),
	];

	it("keeps catalogue order while any row still asks", () => {
		expect(orderGroups(groups, false).map((each) => each.groupSlug)).toEqual(["a", "b", "c"]);
	});

	it("sorts as the practice profile does once nothing asks", () => {
		expect(orderGroups(groups, true).map((each) => each.groupSlug)).toEqual(["c", "b", "a"]);
	});
});
