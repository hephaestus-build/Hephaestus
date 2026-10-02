import { describe, expect, it } from "vitest";

import type { WorkspaceGroupSplit } from "@/api/types.gen";

import {
	estimateSentence,
	orderGroups,
	reachSentence,
	withheldSentence,
} from "./across-workspace-copy";

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
		expect(reachSentence(group({}), "TERM", 23)).toBe(
			"7 of the 23 developers observed here this term are Going well, so it is within reach.",
		);
	});

	it("says the group is hard where most get mixed feedback", () => {
		expect(reachSentence(group({ mixedFeedback: 9 }), "DAYS_30", 26)).toBe(
			"9 of the 26 developers observed here in the last 30 days get Mixed feedback; many find this group hard.",
		);
	});

	it("says the split is held back when it collapsed, and nothing when it was withheld", () => {
		expect(
			reachSentence(group({ shape: "COLLAPSED", hasStanding: 12, noneYet: 6 }), "TERM", 18),
		).toBe(
			"12 of the 18 developers observed here this term have a standing; the split is held back.",
		);
		expect(reachSentence(group({ shape: "WITHHELD" }), "TERM", 18)).toBeUndefined();
	});
});

describe("withheldSentence", () => {
	it("gives only the observed total where the split is withheld", () => {
		expect(withheldSentence(24, "TERM")).toBe(
			"24 developers observed here this term; the split is held back.",
		);
		expect(withheldSentence(1, "DAYS_30")).toBe(
			"1 developer observed here in the last 30 days; the split is held back.",
		);
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
		expect(orderGroups(groups, false).map((each) => each.groupSlug)).toStrictEqual(["a", "b", "c"]);
	});

	it("sorts as the practice profile does once nothing asks", () => {
		expect(orderGroups(groups, true).map((each) => each.groupSlug)).toStrictEqual(["c", "b", "a"]);
	});
});
