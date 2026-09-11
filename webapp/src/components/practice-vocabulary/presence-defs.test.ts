import { describe, expect, it } from "vitest";

import { ASSESSMENT_STATUS_DEFS } from "./assessment-status-defs";
import { PRESENCE_DEFS } from "./presence-defs";

describe("presence vocabulary", () => {
	it("keeps presence neutral for both good and bad observations", () => {
		expect(PRESENCE_DEFS.PRESENT.label).toBe("Present");
		expect(PRESENCE_DEFS.ABSENT.label).toBe("Absent");
		expect(PRESENCE_DEFS.PRESENT.description).toContain("good or bad");
		expect(PRESENCE_DEFS.ABSENT.description).toContain("good or bad");
	});

	it("does not describe absence as inapplicability", () => {
		expect(PRESENCE_DEFS.ABSENT.description).toContain("could have occurred");
		expect(ASSESSMENT_STATUS_DEFS.NOT_APPLICABLE.description).toContain("nothing to judge");
	});
});
