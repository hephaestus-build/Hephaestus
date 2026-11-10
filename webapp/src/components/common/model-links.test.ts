import { describe, expect, it } from "vitest";

import { linkIsAllowed } from "./model-links";

describe("linkIsAllowed", () => {
	const hosts = ["github.com", ".hephaestus.example"];

	it.each([
		["https://github.com/a/b/pull/1", true],
		["https://GitHub.com/a", true],
		["https://team.hephaestus.example/p", true],
		["https://hephaestus.example/p", true],
		["https://evil.example/?q=1", false],
		["https://notgithub.com/a", false],
		["https://github.com.evil.example/a", false],
		["https://user@github.com/a", false],
		["javascript:alert(1)", false],
		["mailto:a@github.com", false],
		["not a url", false],
	])("decides %s", (href, allowed) => {
		expect(linkIsAllowed(href, hosts)).toBe(allowed);
	});

	it("allows the page's own host", () => {
		expect(linkIsAllowed(`${window.location.origin}/w/a`, [])).toBe(true);
	});
});
