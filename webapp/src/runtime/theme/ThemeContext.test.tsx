import { readFileSync } from "node:fs";
import path from "node:path";
import { runInThisContext } from "node:vm";

import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { readThemeCookie } from "./theme-cookie";
import { ThemeProvider, useTheme } from "./ThemeContext";

function clearThemeCookie() {
	// oxlint-disable-next-line unicorn/no-document-cookie -- jsdom has no Cookie Store; the provider under test reads document.cookie.
	document.cookie = "hephaestus-theme=; Path=/; Max-Age=0";
}

function setThemeCookie(value: string) {
	// oxlint-disable-next-line unicorn/no-document-cookie -- as above.
	document.cookie = `hephaestus-theme=${value}; Path=/`;
}

function ThemeButtons() {
	const { setTheme } = useTheme();
	return (
		<>
			<button type="button" onClick={() => setTheme("dark")}>
				Dark
			</button>
			<button type="button" onClick={() => setTheme("system")}>
				System
			</button>
		</>
	);
}

describe("ThemeProvider", () => {
	afterEach(() => {
		localStorage.clear();
		clearThemeCookie();
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

	it("takes the theme from the shared cookie before the per-origin choice", () => {
		setThemeCookie("dark");
		localStorage.setItem("theme", "light");

		render(<ThemeProvider defaultTheme="light" />);

		expect(document.documentElement.dataset.colorMode).toBe("dark");
	});

	it("moves a choice from before the cookie into the cookie once", () => {
		localStorage.setItem("theme", "dark");

		render(<ThemeProvider />);

		expect(readThemeCookie(document.cookie)).toBe("dark");
		expect(localStorage.getItem("theme")).toBeNull();
	});

	it("writes a new choice to the cookie that the other hosts read", () => {
		render(
			<ThemeProvider defaultTheme="light">
				<ThemeButtons />
			</ThemeProvider>,
		);

		fireEvent.click(screen.getByRole("button", { name: "Dark" }));

		expect(readThemeCookie(document.cookie)).toBe("dark");
		expect(document.documentElement.dataset.colorMode).toBe("dark");
	});

	it("keeps a cookie that already holds a choice and clears the per-origin one", () => {
		setThemeCookie("light");
		localStorage.setItem("theme", "dark");

		render(<ThemeProvider />);

		expect(readThemeCookie(document.cookie)).toBe("light");
		expect(localStorage.getItem("theme")).toBeNull();
	});

	it("neither reads nor writes the cookie when it is not persistent", () => {
		setThemeCookie("dark");

		render(
			<ThemeProvider defaultTheme="light" persistent={false}>
				<ThemeButtons />
			</ThemeProvider>,
		);

		expect(document.documentElement.dataset.colorMode).toBe("light");
		fireEvent.click(screen.getByRole("button", { name: "System" }));
		expect(readThemeCookie(document.cookie)).toBe("dark");
	});
});

describe("theme-init.js", () => {
	const script = readFileSync(
		path.join(import.meta.dirname, "../../../public/theme-init.js"),
		"utf8",
	);

	afterEach(() => {
		localStorage.clear();
		clearThemeCookie();
		document.documentElement.className = "";
		delete document.documentElement.dataset.colorMode;
	});

	it("applies the cookie's theme before the app mounts", () => {
		setThemeCookie("dark");

		runInThisContext(script);

		expect(document.documentElement.classList.contains("dark")).toBe(true);
		expect(document.documentElement.dataset.colorMode).toBe("dark");
	});

	it("follows the system when the cookie holds no known theme", () => {
		setThemeCookie("solarized");
		const dark = Object.assign(window.matchMedia("(prefers-color-scheme: dark)"), {
			matches: true,
		});
		const matchMedia = vi.spyOn(window, "matchMedia").mockReturnValue(dark);

		runInThisContext(script);
		matchMedia.mockRestore();

		expect(document.documentElement.dataset.colorMode).toBe("dark");
		expect(document.documentElement.classList.contains("solarized")).toBe(false);
	});

	it("falls back to the per-origin choice from before the cookie", () => {
		localStorage.setItem("theme", "dark");

		runInThisContext(script);

		expect(document.documentElement.dataset.colorMode).toBe("dark");
	});

	// The script cannot import the TypeScript reader, so the two must agree on every cookie string.
	// The test environment's `matchMedia` reports light, so a missing theme paints light.
	it.each([
		["hephaestus-theme=dark", "dark", "dark"],
		["a=1; hephaestus-theme=light; b=2", "light", "light"],
		["hephaestus-theme=solarized; hephaestus-theme=dark", "dark", "dark"],
		["hephaestus-theme=", undefined, "light"],
		["other-hephaestus-theme=dark", undefined, "light"],
	])("reads %j as readThemeCookie does", (cookies, choice, painted) => {
		const cookieGetter = vi.spyOn(document, "cookie", "get").mockReturnValue(cookies);

		runInThisContext(script);
		cookieGetter.mockRestore();

		expect(readThemeCookie(cookies)).toBe(choice);
		expect(document.documentElement.dataset.colorMode).toBe(painted);
	});
});
