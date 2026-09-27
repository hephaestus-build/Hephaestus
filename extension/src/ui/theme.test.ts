// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { applyTheme, followSystemTheme } from "~/ui/theme";

/** The system preference, switchable like a real one. */
const system = Object.assign(new EventTarget(), { matches: false });

function flipSystem(dark: boolean): void {
	system.matches = dark;
	system.dispatchEvent(new Event("change"));
}

const html = document.documentElement;
let stop: () => void = () => undefined;

beforeEach(() => {
	system.matches = false;
	vi.stubGlobal("matchMedia", () => system);
});

afterEach(() => {
	stop();
	vi.unstubAllGlobals();
});

describe("the frame's theme owner", () => {
	it("keeps a palette the provider page chose when the system changes", () => {
		stop = followSystemTheme("light");
		flipSystem(true);
		expect(html.classList.contains("dark")).toBe(false);
		expect(html.dataset.palette).toBe("light");
		applyTheme("dark_high_contrast");
		flipSystem(false);
		expect(html.classList.contains("dark")).toBe(true);
		expect(html.dataset.palette).toBe("dark_high_contrast");
	});

	it("follows the system again once the page switches from dark back to it", () => {
		stop = followSystemTheme("dark");
		applyTheme("system");
		expect(html.classList.contains("dark")).toBe(false);
		expect(html.dataset.palette).toBe("light");
		flipSystem(true);
		expect(html.classList.contains("dark")).toBe(true);
		expect(html.dataset.palette).toBe("dark");
	});

	it("paints dimmed as a dark palette of its own", () => {
		stop = followSystemTheme("dark_dimmed");
		expect(html.classList.contains("dark")).toBe(true);
		expect(html.dataset.palette).toBe("dark_dimmed");
	});
});
