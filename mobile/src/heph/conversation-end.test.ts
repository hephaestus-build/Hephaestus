import { describe, expect, it } from "vitest";

import { coveredBottom, endHidden, follows } from "./conversation-end";

// An iPhone-sized frame: 874 tall, a 34pt home indicator, a 60pt composer.
const SAFE = 34;
const COMPOSER = 60;
const VIEWPORT = 874;

describe("coveredBottom", () => {
	it("is the composer and the home indicator under it while the keyboard is closed", () => {
		expect(
			coveredBottom({ composer: COMPOSER, dockPadding: SAFE, keyboard: 0, safeBottom: SAFE }),
		).toBe(94);
	});

	it("is the composer resting on the keyboard once it opens", () => {
		// The keyboard includes the home indicator, so the composer rises by the rest of it.
		expect(
			coveredBottom({ composer: COMPOSER, dockPadding: SAFE, keyboard: 336, safeBottom: SAFE }),
		).toBe(COMPOSER + 336);
	});

	it("counts the gesture area once where the composer pads it itself", () => {
		// Android: the measured composer already includes the inset and its dock adds none.
		expect(coveredBottom({ composer: 84, dockPadding: 0, keyboard: 300, safeBottom: 24 })).toBe(
			84 + 276,
		);
	});
});

describe("endHidden", () => {
	const covered = coveredBottom({
		composer: COMPOSER,
		dockPadding: SAFE,
		keyboard: 336,
		safeBottom: SAFE,
	});

	it("sees a reply running on under the keyboard, although it is inside the frame", () => {
		// The frame would show all 800pt of content; the keyboard and composer hide the bottom of it.
		expect(endHidden({ offset: 0, viewport: VIEWPORT, content: 800 }, covered)).toBe(true);
	});

	it("reads the end as seen once its last line clears the composer", () => {
		const content = 800;
		const offset = content - (VIEWPORT - covered);
		expect(endHidden({ offset, viewport: VIEWPORT, content }, covered)).toBe(false);
	});

	it("hides the end again when the reader scrolls up from it", () => {
		const content = 800;
		const atEnd = content - (VIEWPORT - covered);
		expect(endHidden({ offset: atEnd - 200, viewport: VIEWPORT, content }, covered)).toBe(true);
	});

	it("sees a short reply that fills the space under a sent turn, with or without the keyboard", () => {
		for (const bottom of [
			covered,
			coveredBottom({ composer: COMPOSER, dockPadding: SAFE, keyboard: 0, safeBottom: SAFE }),
		]) {
			expect(endHidden({ offset: 0, viewport: VIEWPORT, content: 400 }, bottom)).toBe(false);
		}
	});
});

describe("follows", () => {
	it("follows whenever the end is in sight, however it got there", () => {
		for (const following of [true, false]) {
			for (const readerScrolling of [true, false]) {
				expect(follows({ following, readerScrolling, hidden: false })).toBe(true);
			}
		}
	});

	it("stops when the reader scrolls the end out of sight, even behind the keyboard", () => {
		expect(follows({ following: true, readerScrolling: true, hidden: true })).toBe(false);
	});

	it("keeps following while the reply outgrows the screen between the app's own scrolls", () => {
		expect(follows({ following: true, readerScrolling: false, hidden: true })).toBe(true);
	});

	it("stays stopped while the reply grows under someone reading further up", () => {
		expect(follows({ following: false, readerScrolling: false, hidden: true })).toBe(false);
	});
});
