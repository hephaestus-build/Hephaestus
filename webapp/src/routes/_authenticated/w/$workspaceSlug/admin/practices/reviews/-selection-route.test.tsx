import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";
import { parseSearch } from "@/lib/router-search";

import { resultDetail, reviewDetail, reviews } from "@/components/admin/practice-reviews/fixtures";
import { server } from "@/mocks/server";
import { reviewHandlers } from "@/routes/_authenticated/w/$workspaceSlug/admin/practices/reviews/-reviews-server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

const REVIEWS = "/w/acme/admin/practices/reviews";
const REVIEWS_TAB = REVIEWS;

const REVIEW = "3f2b6c1e-8a4d-4f0b-9c7e-2d5a1b0c9e84";
const RESULT = "9a1d3c5e-7b2f-4e6a-8c0d-1f3b5d7e9a2c";
const FIXTURE_REVIEW = "11111111-1111-1111-1111-111111111111";
const FIXTURE_RESULT = "55555555-5555-5555-5555-555555555555";
const ITEM = "42";

const fixtureReview = reviews.find((review) => review.id === FIXTURE_REVIEW);

/** Every list request, recorded, over the review fixture served under the ids above. */
function recordRequests() {
	const urls = { reviews: [] as URL[], feedback: [] as URL[] };
	const emptyPage = {
		content: [],
		number: 0,
		size: 25,
		totalElements: 0,
		totalPages: 0,
		numberOfElements: 0,
		first: true,
		last: true,
		empty: true,
	};
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/reviews", ({ request }) => {
			urls.reviews.push(new URL(request.url));
			return HttpResponse.json({
				...emptyPage,
				content: [{ ...fixtureReview, id: REVIEW }],
				totalElements: 1,
				totalPages: 1,
				numberOfElements: 1,
				empty: false,
			});
		}),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", ({ request }) => {
			urls.feedback.push(new URL(request.url));
			return HttpResponse.json(emptyPage);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/:reviewId", ({ params }) =>
			params.reviewId === REVIEW
				? HttpResponse.json({ ...reviewDetail(FIXTURE_REVIEW), id: REVIEW })
				: undefined,
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/results/:resultId", ({ params }) =>
			params.resultId === RESULT
				? HttpResponse.json({ ...resultDetail(FIXTURE_RESULT), id: RESULT, reviewId: REVIEW })
				: undefined,
		),
		...reviewHandlers(),
	);
	return urls;
}

async function selectionBar() {
	return screen.findByRole("group", { name: "Selection", hidden: true }, ROUTE_RENDER_WAIT);
}

function pathOf(link: HTMLElement) {
	return new URL(String(link.getAttribute("href")), window.location.origin).pathname;
}

function carriedSearch(link: HTMLElement) {
	return parseSearch(new URL(String(link.getAttribute("href")), window.location.origin).search);
}

describe("the selection across practice review tabs", () => {
	/**
	 * Track on a row writes the selection to the address without opening the row; the bar names it,
	 * and every tab link carries it.
	 */
	it("tracks a review from its row and carries it to every tab", async () => {
		recordRequests();
		const { router } = renderRouteAtWithRouter(REVIEWS_TAB);

		const toggle = await screen.findByRole(
			"button",
			{ name: /^Track review of #1423, .+\d:\d{2}/u },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(toggle);

		await waitFor(() =>
			expect(router.state.location.search).toMatchObject({ reviewId: REVIEW, selected: true }),
		);
		expect(router.state.location.search).not.toHaveProperty("detail");
		const bar = await selectionBar();
		await within(bar).findByText(/^Review of #1423 · /u);
		const navigation = screen.getByRole("navigation", { name: "Practice review sections" });
		for (const tab of ["Overview", "Observations", "Feedback"]) {
			expect(
				carriedSearch(within(navigation).getByRole("link", { name: new RegExp(`^${tab}`, "u") })),
			).toStrictEqual({ reviewId: REVIEW, selected: true });
		}
	});

	/**
	 * The same review without `selected` — what a "See all" link writes — is a filter: the list
	 * narrows the same way, the toolbar names it as a pill, and there is no selection bar.
	 */
	it("shows a review the address filters by as a pill, not as the selection", async () => {
		const urls = recordRequests();
		const { router } = renderRouteAtWithRouter(`${REVIEWS}/feedback?reviewId=${REVIEW}`);

		await screen.findByText(/^Review: #1423 · /u, {}, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(urls.feedback.at(-1)?.searchParams.get("reviewId")).toBe(REVIEW));
		expect(screen.queryByRole("group", { name: "Selection" })).toBeNull();
		await userEvent.click(screen.getByRole("button", { name: /^Clear review filter \(#1423/u }));
		await waitFor(() => expect(router.state.location.search).not.toHaveProperty("reviewId"));
	});

	it("narrows the reviews to a tracked item, and Show trace opens its trace", async () => {
		const urls = recordRequests();
		renderRouteAtWithRouter(`${REVIEWS_TAB}?itemId=${ITEM}&selected=true`);

		const bar = await selectionBar();
		await waitFor(() => expect(urls.reviews.at(-1)?.searchParams.get("itemId")).toBe(ITEM));
		const trace = await within(bar).findByRole("link", { name: "Show trace" });
		expect(pathOf(trace)).toBe(REVIEWS_TAB);
		expect(carriedSearch(trace)).toMatchObject({
			itemId: ITEM,
			selected: true,
			detail: `trace:item:${ITEM}`,
		});
	});

	it("narrows the reviews and the feedback to a selected result's review", async () => {
		const urls = recordRequests();
		const selected = `reviewId=${REVIEW}&resultId=${RESULT}&selected=true`;
		renderRouteAtWithRouter(`${REVIEWS}/feedback?${selected}`);

		const bar = await selectionBar();
		await within(bar).findByText("The controller delegates before it does anything else");
		await waitFor(() => expect(urls.feedback.at(-1)?.searchParams.get("reviewId")).toBe(REVIEW));
		expect(urls.feedback.at(-1)?.searchParams.has("resultId")).toBe(false);
		const trace = within(bar).getByRole("link", { name: "Show trace" });
		expect(pathOf(trace)).toBe(`${REVIEWS}/feedback`);
		expect(carriedSearch(trace)).toMatchObject({
			reviewId: REVIEW,
			resultId: RESULT,
			selected: true,
			detail: `trace:result:${RESULT}`,
		});
		const navigation = screen.getByRole("navigation", { name: "Practice review sections" });
		await userEvent.click(within(navigation).getByRole("link", { name: /^Reviews/u }));
		await waitFor(() => expect(urls.reviews.at(-1)?.searchParams.get("reviewId")).toBe(REVIEW));
	});

	it("clears the selection from the bar and from the address", async () => {
		recordRequests();
		const { router } = renderRouteAtWithRouter(
			`${REVIEWS}/results?reviewId=${REVIEW}&selected=true`,
		);

		const bar = await selectionBar();
		await userEvent.click(
			await within(bar).findByRole("button", { name: /^Stop tracking \(Review of #1423/u }),
		);

		await waitFor(() => expect(router.state.location.search).not.toHaveProperty("reviewId"));
		expect(router.state.location.search).not.toHaveProperty("selected");
		await waitFor(() => expect(screen.queryByRole("group", { name: "Selection" })).toBeNull());
	});
});
