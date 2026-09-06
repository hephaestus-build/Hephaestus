import { describe, expect, it } from "vitest";

import { resolveHeaderBadge } from "./version";

describe("resolveHeaderBadge", () => {
	it("shows a production semver as a release linking to its GitHub tag", () => {
		const badge = resolveHeaderBadge("0.73.2", "Production", true);
		expect(badge).toStrictEqual({
			kind: "release",
			label: "v0.73.2",
			href: "https://github.com/hephaestus-build/Hephaestus/releases/tag/v0.73.2",
			tooltip: "View release notes",
			ariaLabel: "View release v0.73.2",
		});
	});

	it("shows an environment pill for staging, never the version", () => {
		const badge = resolveHeaderBadge("c46f9f8", "Staging", false);
		expect(badge).toStrictEqual({ kind: "environment", label: "Staging", tone: "staging" });
	});

	it.each([
		["Preview", "preview"],
		["Local", "local"],
	] as const)("tones the %s pill as %s", (name, tone) => {
		expect(resolveHeaderBadge("anything", name, false)).toStrictEqual({
			kind: "environment",
			label: name,
			tone,
		});
	});

	it("shows a pill, not a dead /releases/tag/vnightly link, when a build injected no version", () => {
		expect(resolveHeaderBadge("nightly", "Production", true).kind).toBe("environment");
	});
});
