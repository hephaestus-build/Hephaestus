import { describe, expect, it } from "vitest";

import { type Box, placeGuide } from "./tour-spotlight";

const DESKTOP = { width: 1280, height: 800 };
const PHONE = { width: 390, height: 800 };
const GUIDE = { width: 360, height: 240 };

const place = (spot: Box | null, viewport = DESKTOP, topMargin?: number) =>
	placeGuide(spot, GUIDE.width, GUIDE.height, viewport, topMargin);

/** The guide's own box, for checks that it never covers the part it points at. */
const guideBox = (placement: ReturnType<typeof place>): Box => ({
	top: placement.top,
	left: placement.left,
	width: GUIDE.width,
	height: GUIDE.height,
});

const covers = (a: Box, b: Box) =>
	a.left < b.left + b.width &&
	b.left < a.left + a.width &&
	a.top < b.top + b.height &&
	b.top < a.top + a.height;

describe("placeGuide", () => {
	it("stands in the middle of the screen, with no arrow, when no part is lit", () => {
		expect(place(null)).toStrictEqual({ top: 280, left: 460, arrow: null });
		// A guide taller than the screen keeps the room it leaves at the top.
		expect(placeGuide(null, 360, 900, DESKTOP, 64).top).toBe(64);
	});

	it("goes beside a part with room on its right, level with it, and points straight across", () => {
		const spot = { top: 200, left: 100, width: 300, height: 80 };
		const placement = place(spot);
		expect(placement.left).toBeGreaterThan(spot.left + spot.width);
		expect(placement.arrow?.from[1]).toBe(placement.arrow?.to[1]);
		expect(placement.arrow?.to[0]).toBe(spot.left + spot.width + 4);
		expect(covers(guideBox(placement), spot)).toBe(false);
	});

	it("goes to the left of a part at the right edge, and points right at it", () => {
		const spot = { top: 200, left: 900, width: 300, height: 80 };
		const placement = place(spot);
		expect(placement.left + GUIDE.width).toBeLessThan(spot.left);
		expect(placement.arrow?.to[0]).toBe(spot.left - 4);
	});

	it("goes below a part as wide as the screen, else above it, with the arrow straight up or down", () => {
		const wide = { top: 100, left: 40, width: 1200, height: 120 };
		const below = place(wide);
		expect(below.top).toBeGreaterThanOrEqual(wide.top + wide.height);
		expect(below.arrow?.from[0]).toBe(below.arrow?.to[0]);

		const low = { ...wide, top: 560 };
		const above = place(low);
		expect(above.top + GUIDE.height).toBeLessThanOrEqual(low.top);
		expect(above.arrow?.to[1]).toBe(low.top - 4);
	});

	it("draws no arrow where the guide would cover the part it points at", () => {
		// A part as large as the screen leaves the guide nowhere to stand beside it.
		const everywhere = { top: 0, left: 0, width: 1280, height: 800 };
		expect(place(everywhere).arrow).toBeNull();
	});

	it("is a sheet over the other half of a phone's screen, with no arrow", () => {
		const high = place({ top: 80, left: 20, width: 300, height: 60 }, PHONE);
		expect(high).toStrictEqual({ top: 800 - 240 - 16, left: 16, arrow: null });

		const low = place({ top: 600, left: 20, width: 300, height: 60 }, PHONE, 72);
		expect(low).toStrictEqual({ top: 72, left: 16, arrow: null });
	});
});
