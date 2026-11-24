import { act, renderHook } from "@testing-library/react";
import { renderToString } from "react-dom/server";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { useSpinDelay } from "./use-spin-delay";

function advance(milliseconds: number) {
	act(() => {
		vi.advanceTimersByTime(milliseconds);
	});
}

function DefaultServerSpinner() {
	return String(useSpinDelay(true));
}

beforeEach(() => {
	vi.useFakeTimers();
});

afterEach(() => {
	vi.useRealTimers();
});

// Timing cases from spin-delay v2.0.1 src/index.test.js. The delayed first mount uses ssr: false.
it("keeps the spinner hidden when loading ends before the delay", () => {
	const { result, rerender } = renderHook(
		(loading) => useSpinDelay(loading, { delay: 200, ssr: false }),
		{ initialProps: true },
	);

	expect(result.current).toBe(false);
	advance(100);
	rerender(false);
	advance(100);
	expect(result.current).toBe(false);
});

it("starts at the delay and hides when loading ends after the minimum", () => {
	const { result, rerender } = renderHook(
		(loading) => useSpinDelay(loading, { delay: 200, minDuration: 0, ssr: false }),
		{ initialProps: true },
	);

	advance(199);
	expect(result.current).toBe(false);
	advance(1);
	expect(result.current).toBe(true);
	advance(99);
	expect(result.current).toBe(true);
	advance(1);
	rerender(false);
	expect(result.current).toBe(false);
});

it("keeps the spinner visible through its minimum duration", () => {
	const { result, rerender } = renderHook(
		(loading) => useSpinDelay(loading, { delay: 200, minDuration: 200, ssr: false }),
		{ initialProps: true },
	);

	advance(199);
	expect(result.current).toBe(false);
	advance(1);
	expect(result.current).toBe(true);
	advance(100);
	rerender(false);
	expect(result.current).toBe(true);
	advance(99);
	expect(result.current).toBe(true);
	advance(1);
	expect(result.current).toBe(false);
});

it("uses a 500 ms delay and a minimum of 200 ms for a new load by default", () => {
	const { result, rerender } = renderHook((loading) => useSpinDelay(loading), {
		initialProps: false,
	});

	rerender(true);
	advance(499);
	expect(result.current).toBe(false);
	advance(1);
	expect(result.current).toBe(true);
	rerender(false);
	advance(199);
	expect(result.current).toBe(true);
	advance(1);
	expect(result.current).toBe(false);
});

it("keeps a default spinner visible from its first client render until the minimum elapses", () => {
	const { result, rerender } = renderHook((loading) => useSpinDelay(loading), {
		initialProps: true,
	});

	expect(result.current).toBe(true);
	advance(0);
	expect(result.current).toBe(true);
	advance(100);
	rerender(false);
	advance(99);
	expect(result.current).toBe(true);
	advance(1);
	expect(result.current).toBe(false);
});

it("shows a spinner immediately on the server by default", () => {
	expect(renderToString(<DefaultServerSpinner />)).toBe("true");
});

it("restarts the full delay for a new load and cleans up its timer in Strict Mode", () => {
	const { result, rerender, unmount } = renderHook(
		(loading) => useSpinDelay(loading, { delay: 200, ssr: false }),
		{ initialProps: true, reactStrictMode: true },
	);

	advance(100);
	rerender(false);
	rerender(true);
	advance(199);
	expect(result.current).toBe(false);
	advance(1);
	expect(result.current).toBe(true);
	unmount();
	expect(vi.getTimerCount()).toBe(0);
});
