import { describe, expect, it } from "vitest";

import { workLinks } from "~/shared/web-app-links";

const APP = "https://heph.example.test";

describe("web app links", () => {
	it("leads every reader to this work's review activity in the workspace that answered", () => {
		expect(
			workLinks(APP, "intro course", { kind: "scm.pull_request", id: "4009" }, false),
		).toStrictEqual({
			trace: `${APP}/w/intro%20course/reviews/scm.pull_request/4009`,
		});
	});

	it("adds this work's reviewed output for a workspace admin, keeping work and workspace", () => {
		expect(workLinks(APP, "intro", { kind: "scm.issue", id: "12" }, true)).toStrictEqual({
			trace: `${APP}/w/intro/reviews/scm.issue/12`,
			reviewDetails: `${APP}/w/intro/admin/practices/reviews/targets/issue/12`,
		});
	});
});
