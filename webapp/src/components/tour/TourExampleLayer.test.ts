import { afterEach, describe, expect, it, vi } from "vitest";

import { contentInset } from "./TourExampleLayer";

/** The app's content area, as the root layout draws it, at `top` in the window. */
function contentArea(top: number) {
	const inset = document.createElement("div");
	inset.dataset.slot = "sidebar-inset";
	const main = document.createElement("main");
	inset.append(main);
	document.body.append(inset);
	vi.spyOn(main, "getBoundingClientRect").mockReturnValue(new DOMRect(256, top, 1024, 800));
}

afterEach(() => {
	document.body.replaceChildren();
	vi.restoreAllMocks();
});

describe("contentInset", () => {
	it("is the whole window without the app's content area", () => {
		expect(contentInset()).toBeNull();
	});

	it("starts under the top bar and beside the sidebar", () => {
		contentArea(64);
		expect(contentInset()).toStrictEqual({ top: 64, left: 256 });
	});

	it("starts at the top of the window when the reader's page is scrolled past the top bar", () => {
		// The page scrolled 267 pixels: the content area starts above the window.
		contentArea(64 - 267);
		expect(contentInset()).toStrictEqual({ top: 0, left: 256 });
	});
});
