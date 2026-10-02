// @vitest-environment jsdom
import { describe, expect, it } from "vitest";

import { githubPalette, providerTheme } from "~/content/theme";

function root(attributes: {
	className?: string;
	colorMode?: string;
	lightTheme?: string;
	darkTheme?: string;
}): HTMLElement {
	const element = document.createElement("html");
	element.className = attributes.className ?? "";
	for (const key of ["colorMode", "lightTheme", "darkTheme"] as const) {
		const value = attributes[key];
		if (value !== undefined) {
			element.dataset[key] = value;
		}
	}
	return element;
}

describe("providerTheme on GitLab", () => {
	it.each([
		[{ className: "gl-light" }, false, "light"],
		[{ className: "gl-light" }, true, "light"],
		[{ className: "gl-dark" }, false, "dark"],
		// GitLab's own script adds `gl-dark` to `gl-system` under a dark system.
		[{ className: "gl-system gl-dark" }, true, "dark"],
		[{ className: "gl-system" }, false, "light"],
		[{ className: "gl-system" }, true, "dark"],
		// A GitLab from before colour modes is light, whatever the system prefers.
		[{}, true, "light"],
	] as const)("reads %o with a dark system %s as %s", (attributes, systemDark, palette) => {
		expect(providerTheme(root(attributes), "GITLAB", systemDark)).toBe(palette);
	});
});

describe("providerTheme on GitHub", () => {
	it("takes the selected theme of the slot the mode picks, dimmed and high contrast included", () => {
		expect(
			providerTheme(root({ colorMode: "dark", darkTheme: "dark_dimmed" }), "GITHUB", false),
		).toBe("dark_dimmed");
		expect(
			providerTheme(
				root({ colorMode: "light", lightTheme: "light_high_contrast", darkTheme: "dark" }),
				"GITHUB",
				true,
			),
		).toBe("light_high_contrast");
		expect(
			providerTheme(root({ colorMode: "dark", darkTheme: "dark_high_contrast" }), "GITHUB", false),
		).toBe("dark_high_contrast");
		expect(
			providerTheme(
				root({ colorMode: "dark", darkTheme: "dark_dimmed_high_contrast" }),
				"GITHUB",
				false,
			),
		).toBe("dark_dimmed_high_contrast");
	});

	it("keeps an explicit mode whatever the system prefers", () => {
		const page = root({ colorMode: "light", lightTheme: "light", darkTheme: "dark_dimmed" });
		expect(providerTheme(page, "GITHUB", false)).toBe("light");
		expect(providerTheme(page, "GITHUB", true)).toBe("light");
	});

	it("lets auto pick the slot by the system, with a different theme in each slot", () => {
		const page = root({
			colorMode: "auto",
			lightTheme: "light_high_contrast",
			darkTheme: "dark_dimmed",
		});
		expect(providerTheme(page, "GITHUB", false)).toBe("light_high_contrast");
		expect(providerTheme(page, "GITHUB", true)).toBe("dark_dimmed");
	});

	it("paints a dark theme someone chose for the light slot as dark", () => {
		expect(
			providerTheme(root({ colorMode: "light", lightTheme: "dark_dimmed" }), "GITHUB", false),
		).toBe("dark_dimmed");
	});

	it("follows the system's light or dark when the page states nothing", () => {
		expect(providerTheme(root({}), "GITHUB", false)).toBe("light");
		expect(providerTheme(root({}), "GITHUB", true)).toBe("dark");
		// GitLab's class means nothing on GitHub.
		expect(providerTheme(root({ className: "gl-dark" }), "GITHUB", false)).toBe("light");
	});
});

describe("githubPalette", () => {
	it.each([
		["dark_colorblind", "dark"],
		["dark_tritanopia_high_contrast", "dark_high_contrast"],
		["light_colorblind_high_contrast", "light_high_contrast"],
		["light_tritanopia", "light"],
		// A name from a newer GitHub keeps its family and contrast; one with no family takes the slot's.
		["dark_dimmed_future", "dark_dimmed"],
		["solarized", "dark"],
	] as const)("reduces %s to the %s palette", (name, palette) => {
		expect(githubPalette(name, "dark")).toBe(palette);
	});

	it("takes the slot's family when the slot has no theme", () => {
		expect(githubPalette(undefined, "light")).toBe("light");
		expect(githubPalette(undefined, "dark")).toBe("dark");
	});
});
