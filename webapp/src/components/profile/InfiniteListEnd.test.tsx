import { render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { InfiniteListEnd, type InfiniteListEndProps } from "./InfiniteListEnd";

type Report = (entries: Pick<IntersectionObserverEntry, "target" | "isIntersecting">[]) => void;

/** An `IntersectionObserver` that reports every element it observes as in view, at once. */
class InViewObserver {
	readonly #report: Report;

	constructor(report: Report) {
		this.#report = report;
	}

	observe(target: Element) {
		this.#report([{ target, isIntersecting: true }]);
	}

	unobserve = vi.fn();
	disconnect = vi.fn();
}

function end(props: Partial<InfiniteListEndProps>) {
	return (
		<InfiniteListEnd
			hasMore
			isLoadingMore={false}
			onLoadMore={vi.fn()}
			moreLabel="View earlier reviews"
			failedLabel="We could not load earlier reviews."
			loadingRow={null}
			{...props}
		/>
	);
}

describe("InfiniteListEnd", () => {
	afterEach(() => {
		vi.unstubAllGlobals();
	});

	it("asks for the next page once a refresh that held it back ends", () => {
		vi.stubGlobal("IntersectionObserver", InViewObserver);
		const onLoadMore = vi.fn();

		const { rerender } = render(end({ isRefreshing: true, onLoadMore }));
		expect(onLoadMore).not.toHaveBeenCalled();

		rerender(end({ isRefreshing: false, onLoadMore }));
		expect(onLoadMore).toHaveBeenCalledOnce();
	});
});
