import { screen, within } from "@testing-library/react";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

import { reviewRangeStart } from "@/components/admin/practice-reviews/review-range";
import { ObserverStub } from "@/test/observers";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

import { OVERVIEW, rangeOverview, serveAttention } from "./-overview-attention-server";

beforeAll(
	async () => loadRouteCode("/w/acme/admin/practices/reviews/overview"),
	ROUTE_RENDER_WAIT.timeout,
);

// jsdom needs an IntersectionObserver stub for paged lists.
beforeEach(() => {
	vi.stubGlobal("IntersectionObserver", ObserverStub);
});

describe("the overview's failure rates", () => {
	/** The week alone fails far more: were any figure to read it, it would show. */
	const busyWeek = {
		...rangeOverview,
		reviews: { ...rangeOverview.reviews, failed: 40 },
		feedback: { ...rangeOverview.feedback, failed: 9 },
	};

	it("counts the failed reviews on the Reviews tile over the range, plain at any share", async () => {
		const { overviewReads } = serveAttention(busyWeek);
		renderRouteAtWithRouter(`${OVERVIEW}?range=30d`);

		const link = await screen.findByRole(
			"link",
			{ name: "6% failed, 5 of 85 finished reviews" },
			ROUTE_RENDER_WAIT,
		);
		const item = link.closest<HTMLElement>("[data-tone]");
		expect(item?.dataset.tone).toBe("secondary");
		expect(item?.querySelector(".text-warning, .text-destructive")).toBeNull();
		expect(item?.textContent).toBe("6% failed");
		// Needs you keeps the approvals only.
		const needsYou = screen.getByRole("region", { name: "Needs you" });
		expect(within(needsYou).queryByText(/failed/u)).toBeNull();
		// No read of a fixed week.
		const weekStart = reviewRangeStart(
			// oxlint-disable-next-line no-restricted-properties -- the route would read its week against the wall clock.
			Date.now(),
			"7d",
		).getTime();
		expect(overviewReads.some((from) => Date.parse(from) === weekStart)).toBe(false);
	});

	it("counts the failed deliveries under Delivery over the range on screen", async () => {
		serveAttention(busyWeek);
		renderRouteAtWithRouter(`${OVERVIEW}?range=30d`);

		const delivery = await screen.findByRole("list", { name: "Delivery" }, ROUTE_RENDER_WAIT);
		await within(delivery).findByRole("link", { name: "2 of 62 sent" }, ROUTE_RENDER_WAIT);
		const failures = within(delivery).getByRole("group", { name: "Failures" });
		expect(failures.textContent).toMatch(/^Feedback failed to deliver · 2 of 62 sent3%/u);
		expect(within(delivery).queryByRole("button", { name: /^Why last/u })).toBeNull();
	});
});
