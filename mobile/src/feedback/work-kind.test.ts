import { describe, expect, it } from "vitest";

import { seenOn, workLabel } from "./work-kind";

describe("workLabel", () => {
	it("uses the provider's own word for a change request", () => {
		expect(workLabel("scm.pull_request", "GITLAB")).toBe("merge request");
		expect(workLabel("scm.pull_request", "GITHUB", 2)).toBe("pull requests");
		expect(workLabel("scm.pull_request", undefined)).toBe("pull or merge request");
	});

	it("keeps a kind this build does not know", () => {
		expect(workLabel("design.figma_file", "GITHUB")).toBe("design.figma_file");
	});
});

describe("seenOn", () => {
	it("counts each kind once, in running text", () => {
		expect(
			seenOn(
				[{ kind: "scm.pull_request" }, { kind: "scm.issue" }, { kind: "scm.pull_request" }],
				"GITHUB",
			),
		).toBe("Seen on 2 pull requests and 1 issue");
	});

	it("uses each piece's provider before the workspace fallback", () => {
		expect(
			seenOn(
				[
					{ kind: "scm.pull_request", provider: "GITHUB" },
					{ kind: "scm.pull_request", provider: "GITLAB" },
				],
				"GITLAB",
			),
		).toBe("Seen on 1 pull request and 1 merge request");
	});

	it("says nothing when there is no evidence", () => {
		expect(seenOn([], "GITHUB")).toBe("");
	});
});
