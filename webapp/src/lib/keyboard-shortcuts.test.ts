import { describe, expect, it } from "vitest";

import { isFeedbackShortcut } from "./keyboard-shortcuts";

const COMMAND_AND_SHIFT: KeyboardEventInit = { ctrlKey: true, shiftKey: true };

const press = (key: string, code: string, modifiers = COMMAND_AND_SHIFT) =>
	new KeyboardEvent("keydown", { key, code, ...modifiers });

describe("isFeedbackShortcut", () => {
	it("opens on the advertised letter with either command key", () => {
		expect(isFeedbackShortcut(press("F", "KeyF"))).toBe(true);
		expect(isFeedbackShortcut(press("f", "KeyF", { metaKey: true, shiftKey: true }))).toBe(true);
	});

	it("follows the letter on a layout that moves it", () => {
		// Dvorak: the key labelled F sits at the physical Y, and the physical F types U.
		expect(isFeedbackShortcut(press("F", "KeyY"))).toBe(true);
		expect(isFeedbackShortcut(press("U", "KeyF"))).toBe(false);
	});

	it("falls back to the physical key on a layout without Latin letters", () => {
		expect(isFeedbackShortcut(press("А", "KeyF"))).toBe(true);
		expect(isFeedbackShortcut(press("Ф", "KeyA"))).toBe(false);
	});

	it("needs both modifiers", () => {
		expect(isFeedbackShortcut(press("F", "KeyF", { shiftKey: true }))).toBe(false);
		expect(isFeedbackShortcut(press("f", "KeyF", { ctrlKey: true }))).toBe(false);
	});
});
