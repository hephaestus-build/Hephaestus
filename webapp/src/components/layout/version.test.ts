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
		expect(badge).toStrictEqual({
			kind: "environment",
			label: "Staging",
			tone: "staging",
			tooltip: "Staging environment",
		});
	});

	it.each([
		["Preview", "preview"],
		["Local", "local"],
	] as const)("tones the %s pill as %s", (name, tone) => {
		expect(resolveHeaderBadge("anything", name, false)).toStrictEqual({
			kind: "environment",
			label: name,
			tone,
			tooltip: `${name} environment`,
		});
	});

	// The environment name stays in the label: the dot's colour is the one part of this pill a
	// colour-blind reader cannot use. `toStrictEqual` on the cases above pins the absence of `link`
	// everywhere else.
	it("names and links the pull request a preview is of, since every preview is called Preview", () => {
		expect(resolveHeaderBadge("c46f9f8", "Preview", false, 2042)).toStrictEqual({
			kind: "environment",
			label: "Preview · PR #2042",
			tone: "preview",
			tooltip: "Preview of pull request #2042",
			href: "https://github.com/hephaestus-build/Hephaestus/pull/2042",
		});
	});

	it("shows a pill, not a dead /releases/tag/vnightly link, when a build injected no version", () => {
		expect(resolveHeaderBadge("nightly", "Production", true).kind).toBe("environment");
	});
});
