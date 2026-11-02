import { describe, expect, it } from "vitest";

import { readTourProgress } from "./tour-progress";

describe("readTourProgress", () => {
	it("reads a stored place and the finished chapters", () => {
		const stored = { place: { chapter: "your-profile", step: "why" }, done: ["how-it-works"] };
		expect(readTourProgress(JSON.stringify(stored))).toStrictEqual(stored);
	});

	it.each([
		["nothing stored", null],
		["text that is not JSON", "{not json"],
		["JSON of another shape", JSON.stringify({ place: 3 })],
	])("starts fresh from %s", (_, raw) => {
		expect(readTourProgress(raw)).toStrictEqual({ place: null, done: [] });
	});
});
