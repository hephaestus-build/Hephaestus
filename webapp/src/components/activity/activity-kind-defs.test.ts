import { describe, expect, it } from "vitest";

import {
	ACTIVITY_KIND_DEFS,
	ACTIVITY_KINDS,
	actionPhrase,
	countPhrase,
} from "./activity-kind-defs";

describe("ACTIVITY_KINDS", () => {
	it("lists every kind the registry defines, in the registry's order", () => {
		expect(ACTIVITY_KINDS).toStrictEqual(Object.keys(ACTIVITY_KIND_DEFS));
	});
});

describe("countPhrase and actionPhrase", () => {
	it("count a comment and name a lifecycle event, in the provider's words", () => {
		expect(countPhrase("CODE_COMMENTED", 1, "GITHUB")).toBe("1 comment on code");
		expect(countPhrase("PULL_REQUEST_MERGED", 3, "GITLAB")).toBe("3 merge requests merged");
		expect(actionPhrase({ kind: "REVIEW_COMMENTED", count: 1 }, "GITHUB")).toBe("commented");
		expect(actionPhrase({ kind: "REVIEW_APPROVED", count: 2 }, "GITHUB")).toBe("approved 2 times");
	});
});
