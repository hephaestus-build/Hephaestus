import { describe, expect, it } from "vitest";

import { docsBaseUrl, docUrl } from "./docs-url";

describe("docsBaseUrl", () => {
	it("sends a release to the docs of the latest release", () => {
		expect(docsBaseUrl("1.0.0")).toBe("https://docs.hephaestus.build/");
		expect(docsBaseUrl("1.0.0-rc.5")).toBe("https://docs.hephaestus.build/");
	});

	it("sends any other build to the docs of main", () => {
		for (const version of ["DEV", "", "0.0.1-cluster", "1.0.0-rc.5-smoke", "1.0"]) {
			expect(docsBaseUrl(version)).toBe("https://docs.hephaestus.build/next/");
		}
	});
});

describe("docUrl", () => {
	it("puts the page below the docs site of the release", () => {
		expect(docUrl("upgradeGuide", "https://docs.hephaestus.build/")).toBe(
			"https://docs.hephaestus.build/admin/upgrade",
		);
		expect(docUrl("upgradeGuide", "https://docs.hephaestus.build/next/")).toBe(
			"https://docs.hephaestus.build/next/admin/upgrade",
		);
	});

	it("opens the section of a target with a heading", () => {
		expect(docUrl("practiceScriptTraceLine", "https://docs.hephaestus.build/")).toBe(
			"https://docs.hephaestus.build/admin/practice-review-operations#the-trace-line",
		);
	});
});
