import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { forgetSectionFold, useSectionOpen } from "./section-folds";

describe("useSectionOpen", () => {
	afterEach(() => {
		forgetSectionFold("test:queue");
		vi.restoreAllMocks();
	});

	it("starts open and remembers a fold for the next visit", () => {
		const first = renderHook(() => useSectionOpen("test:queue"));
		expect(first.result.current[0]).toBe(true);
		act(() => first.result.current[1](false));
		expect(first.result.current[0]).toBe(false);

		const next = renderHook(() => useSectionOpen("test:queue"));
		expect(next.result.current[0]).toBe(false);
	});

	it("starts closed when asked, and remembers an opening for the next visit", () => {
		const first = renderHook(() => useSectionOpen("test:queue", { defaultOpen: false }));
		expect(first.result.current[0]).toBe(false);
		act(() => first.result.current[1](true));

		const next = renderHook(() => useSectionOpen("test:queue", { defaultOpen: false }));
		expect(next.result.current[0]).toBe(true);
	});

	it("is always open without a key", () => {
		const { result } = renderHook(() => useSectionOpen(undefined));
		expect(result.current[0]).toBe(true);
	});

	it("starts open and still folds when storage is unavailable", () => {
		vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
			throw new Error("blocked");
		});
		vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
			throw new Error("blocked");
		});
		const { result } = renderHook(() => useSectionOpen("test:queue"));
		expect(result.current[0]).toBe(true);
		act(() => result.current[1](false));
		expect(result.current[0]).toBe(false);
	});
});
