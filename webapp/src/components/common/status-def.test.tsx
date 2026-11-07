import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";

import {
	UNKNOWN_ACTIVITY_KIND_DEF,
	ACTIVITY_KIND_DEFS,
} from "@/components/activity/activity-kind-defs";
import {
	UNKNOWN_AGENT_PURPOSE_DEF,
	AGENT_PURPOSE_DEFS,
} from "@/components/practice-vocabulary/agent-purpose-defs";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { definitionFor, UNKNOWN_STATUS_DEF } from "./status-def";
import { StatusBadge } from "./StatusBadge";

it("keeps a known value's words and renders a neutral unknown for a new server value", () => {
	expect(definitionFor(OUTCOME_DEFS, "MET").label).toBe("Met");
	render(<StatusBadge def={definitionFor(OUTCOME_DEFS, "FUTURE_OUTCOME")} />);
	screen.getByText("Unknown");
	expect(screen.queryByText("FUTURE_OUTCOME")).toBeNull();
});

it.each(["constructor", "toString", "__proto__"])(
	"does not interpret %s as a known definition",
	(value) => {
		expect(definitionFor(OUTCOME_DEFS, value)).toBe(UNKNOWN_STATUS_DEF);
	},
);

it("retains the typed fields that rich registries need for an unknown value", () => {
	const purpose = definitionFor(AGENT_PURPOSE_DEFS, "NEW_PURPOSE", UNKNOWN_AGENT_PURPOSE_DEF);
	expect(purpose.title).toBe("Unknown model purpose");
	const activity = definitionFor(ACTIVITY_KIND_DEFS, "NEW_ACTIVITY", UNKNOWN_ACTIVITY_KIND_DEF);
	expect(activity.noun("GITHUB")).toStrictEqual({
		one: "unknown activity",
		many: "unknown activities",
	});
});

it("supports registries of explanatory text with the same unknown-value policy", () => {
	expect(definitionFor({ KNOWN: "Known reason" }, "NEW_REASON", "Unknown reason")).toBe(
		"Unknown reason",
	);
});
