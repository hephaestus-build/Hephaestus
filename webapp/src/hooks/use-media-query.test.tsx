import { act, renderHook } from "@testing-library/react";
import { renderToString } from "react-dom/server";
import { assert, expect, it, vi } from "vitest";

import { useMediaQuery } from "./use-media-query";

function matchingQuery(query: string) {
	const target = new EventTarget();
	const media = Object.assign(window.matchMedia(query), {
		matches: true,
		addEventListener: vi.fn(target.addEventListener.bind(target)),
		removeEventListener: vi.fn(target.removeEventListener.bind(target)),
	});
	return {
		media,
		noLongerMatches() {
			act(() => {
				Object.assign(media, { matches: false });
				target.dispatchEvent(new Event("change"));
			});
		},
	};
}

it("reads the current media query and releases its listener when it changes or unmounts", () => {
	const motionQuery = "(prefers-reduced-motion: reduce)";
	const touchQuery = "(pointer: coarse)";
	const motion = matchingQuery(motionQuery);
	const touch = matchingQuery(touchQuery);
	const queries = new Map([
		[motionQuery, motion.media],
		[touchQuery, touch.media],
	]);
	using _media = vi.spyOn(window, "matchMedia").mockImplementation((query) => {
		const match = queries.get(query);
		assert.isDefined(match);
		return match;
	});

	const { result, rerender, unmount } = renderHook(({ query }) => useMediaQuery(query), {
		initialProps: { query: motionQuery },
	});
	expect(result.current).toBe(true);
	motion.noLongerMatches();
	expect(result.current).toBe(false);

	rerender({ query: touchQuery });
	expect(result.current).toBe(true);
	expect(motion.media.removeEventListener.mock.calls).toStrictEqual(
		motion.media.addEventListener.mock.calls,
	);
	touch.noLongerMatches();
	expect(result.current).toBe(false);

	unmount();
	expect(touch.media.removeEventListener.mock.calls).toStrictEqual(
		touch.media.addEventListener.mock.calls,
	);
});

function ServerQuery() {
	return String(useMediaQuery("(prefers-reduced-motion: reduce)"));
}

it("uses the same false snapshot on the server without reading the browser", () => {
	using matchMedia = vi.spyOn(window, "matchMedia");
	expect(renderToString(<ServerQuery />)).toBe("false");
	expect(matchMedia).not.toHaveBeenCalled();
});
