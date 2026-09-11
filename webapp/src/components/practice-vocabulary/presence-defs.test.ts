import { describe, expect, it } from "vitest";

import { ASSESSMENT_STATUS_DEFS } from "./assessment-status-defs";
import { PRESENCE_DEFS } from "./presence-defs";

describe("presence vocabulary", () => {
	it("keeps presence neutral for desirable and undesirable targets", () => {
		expect(PRESENCE_DEFS.PRESENT.label).toBe("Present");
		expect(PRESENCE_DEFS.ABSENT.label).toBe("Absent");
		expect(PRESENCE_DEFS.PRESENT.description).toContain("GOOD/BAD assessment");
		expect(PRESENCE_DEFS.ABSENT.description).toContain(
			"negative for GOOD targets and positive for BAD targets",
		);
	});

	it("does not describe absence as inapplicability", () => {
		expect(PRESENCE_DEFS.ABSENT.description).toContain("applicable target criterion");
		expect(ASSESSMENT_STATUS_DEFS.NOT_APPLICABLE.description).toContain("nothing to judge");
	});
});
