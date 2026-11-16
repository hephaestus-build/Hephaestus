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
			ariaLabel: "View release v0.73.2 (opens in a new tab)",
		});
	});

	it("shows a production pre-release as a release too, not the environment name", () => {
		const badge = resolveHeaderBadge("1.0.0-rc.1", "Production", true);
		expect(badge).toStrictEqual({
			kind: "release",
			label: "v1.0.0-rc.1",
			href: "https://github.com/hephaestus-build/Hephaestus/releases/tag/v1.0.0-rc.1",
			tooltip: "View release notes",
			ariaLabel: "View release v1.0.0-rc.1 (opens in a new tab)",
		});
	});

	it.each(["c46f9f8", "nightly", "1.0", "1.0.0-"])(
		"keeps the environment pill in production for %s, which has no release page",
		(version) => {
			expect(resolveHeaderBadge(version, "Production", true).kind).toBe("environment");
		},
	);

	it("shows an environment pill for staging, never the version", () => {
		const badge = resolveHeaderBadge("c46f9f8", "Staging", false);
		expect(badge).toStrictEqual({
			kind: "environment",
			label: "Staging",
			tone: "staging",
			tooltip: "Staging environment",
		});
	});

	it("tones the Local pill as local", () => {
		expect(resolveHeaderBadge("anything", "Local", false)).toStrictEqual({
			kind: "environment",
			label: "Local",
			tone: "local",
			tooltip: "Local environment",
		});
	});

	it("shows a pill, not a dead /releases/tag/vnightly link, when a build injected no version", () => {
		expect(resolveHeaderBadge("nightly", "Production", true).kind).toBe("environment");
	});
});
