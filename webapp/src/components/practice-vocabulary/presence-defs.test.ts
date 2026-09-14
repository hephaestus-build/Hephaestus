import { describe, expect, it } from "vitest";

import { ASSESSMENT_STATUS_DEFS } from "./assessment-status-defs";
import { PRESENCE_DEFS } from "./presence-defs";

describe("presence vocabulary", () => {
	it("keeps presence neutral for desirable and undesirable behaviors", () => {
		expect(PRESENCE_DEFS.PRESENT.label).toBe("Present");
		expect(PRESENCE_DEFS.ABSENT.label).toBe("Absent");
		expect(PRESENCE_DEFS.PRESENT.description).toContain("GOOD/BAD assessment");
		expect(PRESENCE_DEFS.ABSENT.description).toContain(
			"negative for desirable behavior and positive for undesirable behavior",
		);
	});

	it("does not describe absence as inapplicability", () => {
		expect(PRESENCE_DEFS.ABSENT.description).toContain("applicable, bounded search");
		expect(ASSESSMENT_STATUS_DEFS.NOT_APPLICABLE.description).toContain("nothing to judge");
	});
});
