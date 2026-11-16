import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { readTourProgress, useTourProgress } from "./tour-progress";
import { tourRecordKey } from "./tour-start";

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

describe("useTourProgress", () => {
	// The test setup remembers the offer for the signed-in test account; these cases start empty.
	beforeEach(() => localStorage.clear());
	afterEach(() => {
		vi.restoreAllMocks();
		localStorage.clear();
	});

	it("keeps this account's place and finished chapters under its own record", () => {
		const { result } = renderHook(() => useTourProgress("5"));
		act(() => result.current.stopAt("how-it-works", "work"));
		act(() => result.current.finish("how-it-works"));
		expect(result.current.progress).toStrictEqual({ place: null, done: ["how-it-works"] });
		expect(readTourProgress(localStorage.getItem(tourRecordKey("progress", "5")))).toStrictEqual(
			result.current.progress,
		);
		// Finishing a chapter twice keeps it once.
		act(() => result.current.finish("how-it-works"));
		expect(result.current.progress.done).toStrictEqual(["how-it-works"]);
		act(() => result.current.reset());
		expect(result.current.progress).toStrictEqual({ place: null, done: [] });
	});

	it("never reads another account's record", () => {
		localStorage.setItem(
			tourRecordKey("progress", "7"),
			JSON.stringify({ place: null, done: ["how-it-works"] }),
		);
		const { result } = renderHook(() => useTourProgress("5"));
		expect(result.current.progress.done).toStrictEqual([]);
	});

	it("stores nothing without an account, and lasts until the page closes when storage refuses", () => {
		const { result: anonymous } = renderHook(() => useTourProgress(undefined));
		act(() => anonymous.current.finish("your-profile"));
		expect(anonymous.current.progress.done).toStrictEqual(["your-profile"]);
		expect(localStorage).toHaveLength(0);

		vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
			throw new DOMException("Full", "QuotaExceededError");
		});
		const { result } = renderHook(() => useTourProgress("5"));
		act(() => result.current.finish("going-deeper"));
		expect(result.current.progress.done).toStrictEqual(["going-deeper"]);
	});
});
