import { render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { ThemeProvider } from "./ThemeContext";

describe("ThemeProvider", () => {
	afterEach(() => {
		localStorage.clear();
		document.documentElement.className = "";
		delete document.documentElement.dataset.colorMode;
	});

	// `localStorage` outlives any rename of the themes, so a value from an older build must not reach
	// the document as a class nobody styles.
	it("falls back to the default theme when the stored one is not a theme this build knows", () => {
		localStorage.setItem("theme", "solarized");

		render(<ThemeProvider defaultTheme="light" />);

		expect(document.documentElement.dataset.colorMode).toBe("light");
	});
});
