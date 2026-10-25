import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, assert, describe, expect, it, vi } from "vitest";

import type { ReviewSummary } from "@/api/types.gen";
import {
	reviewAccounts,
	reviewFeedback,
	reviewProblems,
	reviewResults,
	reviews,
} from "@/components/admin/practice-reviews/fixtures";
import { developerLabel } from "@/components/admin/practice-reviews/review-format";
import { ACTIVE_REVIEW_POLL_MS } from "@/components/admin/practice-reviews/review-search";
import { reviewHandlers } from "@/routes/_authenticated/w/$workspaceSlug/admin/practices/reviews/-reviews-server";

import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { ownMembership } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { precedes } from "@/test/dom";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

/**
 * Repeated params and comma-joined params both, because which one the generated client emits is its
 * business and an assertion that guesses wrong passes or fails for the wrong reason.
 */
function values(url: URL | undefined, name: string): string[] {
	return (url?.searchParams.getAll(name) ?? [])
		.flatMap((value) => value.split(","))
		.filter(Boolean);
}

const PAGE_SIZE = 25;

/** Totals and the admin navigation peek share these endpoints, but are not the full list operation. */
function isListPage(url: URL): boolean {
	return url.searchParams.get("size") === String(PAGE_SIZE);
}

function springPage<T>(content: T[], number = 0, totalPages = 1, totalElements = content.length) {
	return {
		content,
		number,
		size: PAGE_SIZE,
		totalElements,
		totalPages,
		numberOfElements: content.length,
		first: number === 0,
		last: number >= totalPages - 1,
		empty: content.length === 0,
	};
}

function pageOf<T>(rows: T[]) {
	return springPage(rows);
}

const emptyPage = pageOf([]);

const listed =
	(urls: URL[]) =>
	({ request }: { request: Request }) => {
		const url = new URL(request.url);
		if (isListPage(url)) {
			urls.push(url);
		}
		return HttpResponse.json(emptyPage);
	};

function recordRequests() {
	server.use(...reviewHandlers());
	const reviewUrls: URL[] = [];
	const resultUrls: URL[] = [];
	const problemUrls: URL[] = [];
	const feedbackUrls: URL[] = [];
	const locationCountUrls: URL[] = [];
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces/:workspaceSlug/members", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/results", listed(resultUrls)),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/problems", listed(problemUrls)),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", listed(feedbackUrls)),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/locations", ({ request }) => {
			locationCountUrls.push(new URL(request.url));
			return HttpResponse.json([]);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/reviews", listed(reviewUrls)),
	);
	return { reviewUrls, resultUrls, problemUrls, feedbackUrls, locationCountUrls };
}

function activeReviewsHandler(getReview: () => ReviewSummary, reads: URL[]) {
	return http.get("*/workspaces/:workspaceSlug/practices/reviews", ({ request }) => {
		const url = new URL(request.url);
		if (isListPage(url)) {
			reads.push(url);
		}
		const review = getReview();
		const rows = values(url, "status").includes(review.status) ? [review] : [];
		return HttpResponse.json(pageOf(rows));
	});
}

const [FIRST_PAGE, SECOND_PAGE] = twoCompleteReviews();

function twoCompleteReviews(): [ReviewSummary, ReviewSummary] {
	const complete = reviews.filter((review) => review.status === "COMPLETE");
	const first = complete[0];
	const second = complete.find((review) => review.item.label !== first?.item.label);
	if (!first || !second) {
		throw new Error("The fixture has fewer than two complete reviews of different items");
	}
	return [first, second];
}

function reviewLinkName(review: ReviewSummary): RegExp {
	return new RegExp(`^Open review of ${review.item.label},`, "u");
}

/**
 * The reviews endpoint with two pages, every request recorded. A review started after the first page
 * loaded pushed that page's row down, so the second page starts with it again.
 */
function serveReviewPages(reviewUrls: URL[]) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/reviews", ({ request }) => {
			const url = new URL(request.url);
			if (isListPage(url)) {
				reviewUrls.push(url);
			}
			const number = Number(url.searchParams.get("page"));
			return HttpResponse.json(
				springPage(number === 0 ? [FIRST_PAGE] : [FIRST_PAGE, SECOND_PAGE], number, 2, 3),
			);
		}),
	);
}

/**
 * One list endpoint as the server pages it: three rows over two flat pages, the first holding one.
 * Returns the page numbers the list asked for, leaving out the tab counts.
 */
function serveTwoFlatPages(tab: string, [first, second, third]: readonly unknown[]): string[] {
	assert(first !== undefined && second !== undefined && third !== undefined);
	const pageReads: string[] = [];
	const pages = [[first], [second, third]];
	server.use(
		http.get(`*/workspaces/:workspaceSlug/practices/reviews/${tab}`, ({ request }) => {
			const url = new URL(request.url);
			const number = Number(url.searchParams.get("page"));
			if (isListPage(url)) {
				pageReads.push(String(number));
			}
			return HttpResponse.json(springPage(pages[number] ?? [], number, 2, 3));
		}),
	);
	return pageReads;
}

describe("practice review list routes", () => {
	afterEach(() => {
		vi.unstubAllGlobals();
	});

	it("asks the endpoint for the ordering the URL chose, under the name the endpoint uses", async () => {
		const { resultUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/results?order=ACTIONABILITY&severity=["MAJOR"]',
		);
		await screen.findByText("No observations match these filters", undefined, ROUTE_RENDER_WAIT);

		const requested = resultUrls.at(-1);
		expect(requested?.searchParams.get("sort")).toBe("ACTIONABILITY");
		expect(requested?.searchParams.get("order")).toBeNull();
		// The rest of the query travelled too, so a passing `sort` cannot be the only surviving param.
		expect(values(requested, "severity")).toContain("MAJOR");
		expect(requested?.searchParams.get("size")).toBe("25");
	});

	/**
	 * The feedback list reads its ordering from the same URL word, and the approvals queue on the
	 * overview links here oldest first, so a lost ordering would put the newest proposal at the top of
	 * a queue that is worked through from the other end.
	 */
	it("asks the feedback endpoint for the ordering the URL chose, under the same names", async () => {
		const { feedbackUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/feedback?order=OLDEST&state=["AWAITING_APPROVAL"]',
		);
		await screen.findByText("No feedback matches these filters", undefined, ROUTE_RENDER_WAIT);

		const requested = feedbackUrls.at(-1);
		expect(requested?.searchParams.get("sort")).toBe("OLDEST");
		expect(requested?.searchParams.get("order")).toBeNull();
		expect(values(requested, "state")).toStrictEqual(["AWAITING_APPROVAL"]);
	});

	/**
	 * The time order is part of the shared scope: chosen once in the bar above the tabs, it orders
	 * every list, travels with the tab links, outlasts the tab's Reset and leaves with "Clear scope".
	 */
	it("keeps the time order chosen above the tabs on every list until the scope is cleared", async () => {
		const { reviewUrls, resultUrls, problemUrls, feedbackUrls } = recordRequests();

		const { router } = renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/feedback?state=["READY"]',
		);
		await screen.findByText("No feedback matches these filters", undefined, ROUTE_RENDER_WAIT);
		const scope = screen.getByRole("group", { name: "Applies to every tab" });
		await userEvent.click(within(scope).getByRole("combobox", { name: "Sort" }));
		await userEvent.click(await screen.findByRole("option", { name: "Oldest first" }));
		await waitFor(() => expect(router.state.location.search).toMatchObject({ order: "OLDEST" }));
		await waitFor(() => expect(feedbackUrls.at(-1)?.searchParams.get("sort")).toBe("OLDEST"));

		await userEvent.click(screen.getByRole("button", { name: "Reset" }));
		await waitFor(() => expect(values(feedbackUrls.at(-1), "state")).toStrictEqual([]));
		expect(router.state.location.search).toMatchObject({ order: "OLDEST" });

		const tabs = screen.getByRole("navigation", { name: "Practice review sections" });
		for (const [tab, urls] of [
			["Observations", resultUrls],
			["Problems", problemUrls],
			["Reviews", reviewUrls],
		] as const) {
			await userEvent.click(within(tabs).getByRole("link", { name: new RegExp(`^${tab}`, "u") }));
			await waitFor(() => expect(urls.at(-1)?.searchParams.get("sort")).toBe("OLDEST"));
			expect(
				within(screen.getByRole("group", { name: "Applies to every tab" })).getByRole("combobox", {
					name: "Sort",
				}).textContent,
			).toContain("Oldest first");
		}

		await userEvent.click(screen.getByRole("button", { name: "Clear scope" }));
		await waitFor(() => expect(reviewUrls.at(-1)?.searchParams.get("sort")).toBeNull());
		expect(router.state.location.search.order).toBeUndefined();
	});

	/** "Most actionable first" is the results list's own: no other list has it, so no link carries it. */
	it("keeps the results list's own ordering on that list alone", async () => {
		const { resultUrls, feedbackUrls } = recordRequests();

		renderRouteAtWithRouter(
			"/w/acme/admin/practices/reviews/results?developerId=7&order=ACTIONABILITY",
		);
		await screen.findByText("No observations match these filters", undefined, ROUTE_RENDER_WAIT);
		const scope = screen.getByRole("group", { name: "Applies to every tab" });
		expect(within(scope).getByRole("combobox", { name: "Sort" }).textContent).toContain(
			"Most actionable first",
		);

		// "Clear scope" ends the shared scope; the list's own ordering is not part of it.
		await userEvent.click(within(scope).getByRole("button", { name: "Clear scope" }));
		await waitFor(() => expect(resultUrls.at(-1)?.searchParams.get("developerId")).toBeNull());
		expect(resultUrls.at(-1)?.searchParams.get("sort")).toBe("ACTIONABILITY");

		const tabs = screen.getByRole("navigation", { name: "Practice review sections" });
		await userEvent.click(within(tabs).getByRole("link", { name: /^Feedback/u }));
		await waitFor(() => expect(feedbackUrls.length).toBeGreaterThan(0));
		expect(feedbackUrls.at(-1)?.searchParams.get("sort")).toBeNull();
		await userEvent.click(
			within(screen.getByRole("group", { name: "Applies to every tab" })).getByRole("combobox", {
				name: "Sort",
			}),
		);
		expect(screen.queryByRole("option", { name: "Most actionable first" })).toBeNull();
	});

	/** The default ordering is the server's, so nothing is sent rather than a guess at its name. */
	it("sends no ordering when the reader has not chosen one", async () => {
		const { resultUrls } = recordRequests();

		renderRouteAtWithRouter("/w/acme/admin/practices/reviews/results");
		await screen.findByText("No observations yet", undefined, ROUTE_RENDER_WAIT);

		expect(resultUrls.at(-1)?.searchParams.get("sort")).toBeNull();
	});

	it("sends no feedback ordering when the reader has not chosen one", async () => {
		const { feedbackUrls } = recordRequests();

		renderRouteAtWithRouter("/w/acme/admin/practices/reviews/feedback");
		await screen.findByText("No feedback yet", undefined, ROUTE_RENDER_WAIT);

		expect(feedbackUrls.at(-1)?.searchParams.get("sort")).toBeNull();
	});

	/**
	 * The overview's counts link here as outcome and marked-incorrect filters, so a count that opens a
	 * list which forgot either shows more rows than the number the reader clicked.
	 */
	it("sends the outcome and marked-incorrect filters to the endpoint", async () => {
		const { resultUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/results?outcome=["NOT_MET"]&markedIncorrect=true',
		);
		await screen.findByText("No observations match these filters", undefined, ROUTE_RENDER_WAIT);

		const requested = resultUrls.at(-1);
		expect(values(requested, "outcome")).toStrictEqual(["NOT_MET"]);
		expect(requested?.searchParams.get("markedIncorrect")).toBe("true");
	});

	it("expands a withholding family to the stop reasons the endpoint filters on", async () => {
		const { feedbackUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/feedback?withheldFamily=["HOUSEKEEPING"]',
		);
		await screen.findByText("No feedback matches these filters", undefined, ROUTE_RENDER_WAIT);

		const reasons = values(feedbackUrls.at(-1), "stopReason");
		expect(reasons).toContain("NOTHING_NEW");
		expect(reasons).not.toContain("HOUSEKEEPING");
		expect(feedbackUrls.at(-1)?.searchParams.get("withheldFamily")).toBeNull();
	});

	it("sends the severity and scope filters to the problems endpoint", async () => {
		const { problemUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/problems?severity=["MAJOR"]&developerId=7&practiceSlug=["thin-controllers"]&groupSlug=["design"]&from=2026-09-01&to=2026-09-10&resultId=3f2b6c1e-8a4d-4f0b-9c7e-2d5a1b0c9e84',
		);
		await screen.findByText("No problems match these filters", undefined, ROUTE_RENDER_WAIT);

		const requested = problemUrls.at(-1);
		expect(values(requested, "severity")).toStrictEqual(["MAJOR"]);
		expect(requested?.searchParams.get("developerId")).toBe("7");
		expect(values(requested, "practiceSlug")).toStrictEqual(["thin-controllers"]);
		expect(values(requested, "groupSlug")).toStrictEqual(["design"]);
		expect(requested?.searchParams.get("from")).not.toBeNull();
		expect(requested?.searchParams.get("to")).not.toBeNull();
		expect(requested?.searchParams.get("resultId")).toBe("3f2b6c1e-8a4d-4f0b-9c7e-2d5a1b0c9e84");
	});

	it("asks for the problems of the severity chosen, and for all of them once it is cleared", async () => {
		const { problemUrls } = recordRequests();

		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices/reviews/problems");
		const severity = await screen.findByRole("combobox", { name: "Severity" }, ROUTE_RENDER_WAIT);
		await userEvent.click(severity);
		await userEvent.click(await screen.findByRole("option", { name: /Major/u }));
		await waitFor(() => expect(router.state.location.searchStr).toContain("severity=MAJOR"));
		await waitFor(() => expect(values(problemUrls.at(-1), "severity")).toStrictEqual(["MAJOR"]));

		await userEvent.click(await screen.findByRole("option", { name: /Major/u }));
		await waitFor(() => expect(router.state.location.searchStr).not.toContain("severity"));
		await waitFor(() => expect(values(problemUrls.at(-1), "severity")).toStrictEqual([]));
	});

	/**
	 * A practice's feedback count on the overview links here filtered to that practice, so a list that
	 * dropped the practice would show every piece of feedback under a number that counted a few.
	 */
	it("sends the practice filter to the feedback endpoint", async () => {
		const { feedbackUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/feedback?practiceSlug=["thin-controllers"]',
		);
		await screen.findByText("No feedback matches these filters", undefined, ROUTE_RENDER_WAIT);

		expect(values(feedbackUrls.at(-1), "practiceSlug")).toStrictEqual(["thin-controllers"]);
	});

	it("sends the location filter to the feedback endpoint", async () => {
		const { feedbackUrls, locationCountUrls } = recordRequests();

		renderRouteAtWithRouter("/w/acme/admin/practices/reviews/feedback?location=GITLAB");
		await screen.findByText("No feedback matches these filters", undefined, ROUTE_RENDER_WAIT);

		expect(feedbackUrls.at(-1)?.searchParams.get("location")).toBe("GITLAB");
		expect(locationCountUrls).toHaveLength(0);
	});

	it("counts everything for the developer filtered to, and nothing narrower", async () => {
		const { feedbackUrls, locationCountUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/feedback?developerId=9&location=SLACK&state=["DELIVERED"]',
		);
		await screen.findByText("No feedback for this developer yet", undefined, ROUTE_RENDER_WAIT);

		expect(feedbackUrls.at(-1)?.searchParams.get("developerId")).toBe("9");
		const counts = locationCountUrls.at(-1);
		expect(counts?.searchParams.get("developerId")).toBe("9");
		expect(counts?.searchParams.get("location")).toBeNull();
		expect(values(counts, "state")).toStrictEqual([]);
	});

	it("names a row's developer as text, so the row opens one thing", async () => {
		recordRequests();
		const [feedback] = reviewFeedback;
		assert(feedback);
		const name = developerLabel(feedback.developer);
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", () =>
				HttpResponse.json(pageOf([feedback])),
			),
		);

		renderRouteAtWithRouter("/w/acme/admin/practices/reviews/feedback");
		await screen.findByText(name, undefined, ROUTE_RENDER_WAIT);

		expect(screen.queryByRole("link", { name: `All feedback for ${name}` })).toBeNull();
	});

	/**
	 * A running review changes after the list loaded, also under a status filter: the list follows
	 * the active reviews it shows until they stop, reads itself again, and then stops asking.
	 */
	it("follows a running review until it stops, then stops asking", async () => {
		vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
		try {
			recordRequests();
			const polls: URL[] = [];
			let review: ReviewSummary = { ...FIRST_PAGE, status: "RUNNING", completedAt: null };
			server.use(activeReviewsHandler(() => review, polls));

			renderRouteAtWithRouter('/w/acme/admin/practices/reviews?status=["RUNNING","FAILED"]');
			await screen.findByRole("link", { name: reviewLinkName(FIRST_PAGE) }, ROUTE_RENDER_WAIT);
			const table = screen.getByRole("table", { name: "Practice reviews, newest first" });
			await vi.advanceTimersByTimeAsync(ACTIVE_REVIEW_POLL_MS);
			// The interval is faked, so the waits poll on vitest's own timer rather than on setInterval.
			await vi.waitFor(() => expect(polls.length).toBeGreaterThan(0));
			expect(values(polls.at(-1), "status")).toStrictEqual(["RUNNING", "FAILED"]);
			expect(within(table).queryAllByText("Failed")).toHaveLength(0);

			review = { ...review, status: "FAILED" };
			const listReads = polls.length;
			await vi.advanceTimersByTimeAsync(ACTIVE_REVIEW_POLL_MS);
			await vi.waitFor(() => expect(polls.length).toBeGreaterThan(listReads));
			await within(table).findAllByText("Failed", undefined, ROUTE_RENDER_WAIT);

			const settled = polls.length;
			await vi.advanceTimersByTimeAsync(ACTIVE_REVIEW_POLL_MS * 2);
			expect(polls).toHaveLength(settled);
		} finally {
			vi.useRealTimers();
		}
	});

	it("sends the shared developer, practice, group, range and review to the reviews endpoint", async () => {
		const { reviewUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews?developerId=7&practiceSlug=["thin-controllers"]&groupSlug=["design"]&from=2026-09-01&to=2026-09-10&reviewId=3f2b6c1e-8a4d-4f0b-9c7e-2d5a1b0c9e84',
		);
		await screen.findByText("No reviews match these filters", undefined, ROUTE_RENDER_WAIT);

		const requested = reviewUrls.at(-1);
		expect(requested?.searchParams.get("developerId")).toBe("7");
		expect(values(requested, "practiceSlug")).toStrictEqual(["thin-controllers"]);
		expect(values(requested, "groupSlug")).toStrictEqual(["design"]);
		expect(requested?.searchParams.get("from")).not.toBeNull();
		expect(requested?.searchParams.get("to")).not.toBeNull();
		// A review the address filters by narrows the list to itself.
		expect(requested?.searchParams.get("reviewId")).toBe("3f2b6c1e-8a4d-4f0b-9c7e-2d5a1b0c9e84");
	});

	it("sends the group filter to the feedback endpoint", async () => {
		const { feedbackUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/feedback?groupSlug=["design"]&from=2026-09-06&to=2026-10-05&state=["WITHHELD","REJECTED","REPLACED"]',
		);
		await screen.findByText("No feedback matches these filters", undefined, ROUTE_RENDER_WAIT);

		const requested = feedbackUrls.at(-1);
		expect(values(requested, "groupSlug")).toStrictEqual(["design"]);
		expect(values(requested, "state")).toStrictEqual(["WITHHELD", "REJECTED", "REPLACED"]);
	});

	/**
	 * The scope applies to every tab, so it is above the tabs, with the tracked record; each tab's own
	 * filters, its Reset and its count are below them.
	 */
	it.each([
		["", "Created", "Status"],
		["results", "Recorded", "Outcome"],
		["problems", "Recorded", "Severity"],
		["feedback", "Composed", "Delivery"],
	])(
		"puts the scope above the tabs and the %s tab's own filters below them",
		async (tab, date, own) => {
			recordRequests();
			renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews/${tab}?from=2026-09-01&to=2026-09-10`,
			);

			const scope = await screen.findByRole(
				"group",
				{ name: "Applies to every tab" },
				ROUTE_RENDER_WAIT,
			);
			const sections = screen.getByRole("navigation", { name: "Practice review sections" });
			const filters = screen.getByRole("group", { name: "Filters" });
			expect(precedes(scope, sections)).toBe(true);
			expect(precedes(sections, filters)).toBe(true);
			within(scope).getByRole("combobox", { name: "Developer" });
			within(scope).getByRole("button", { name: new RegExp(`^${date}`, "u") });
			within(scope).getByRole("button", { name: "Clear scope" });
			within(filters).getByRole("combobox", { name: own });
			expect(within(filters).queryByRole("combobox", { name: "Developer" })).toBeNull();
			expect(within(filters).queryByRole("button", { name: "Clear scope" })).toBeNull();
		},
	);

	/** The overview keeps the scope it does not apply in the same row above the tabs. */
	it("keeps the scope above the tabs on the overview, marked as not applied", async () => {
		recordRequests();
		renderRouteAtWithRouter("/w/acme/admin/practices/reviews/overview?developerId=7");

		const scope = await screen.findByRole(
			"group",
			{ name: "Applies to every tab" },
			ROUTE_RENDER_WAIT,
		);
		expect(
			precedes(scope, screen.getByRole("navigation", { name: "Practice review sections" })),
		).toBe(true);
		within(scope).getByText("Not applied on the overview");
	});

	/** Reset clears the list's own facets; the shared scope stays for this tab and the next. */
	it("keeps the shared scope when the reader resets the list's own facets", async () => {
		const { resultUrls } = recordRequests();

		const { router } = renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/results?developerId=7&outcome=["NOT_MET"]',
		);
		await screen.findByText("No observations match these filters", undefined, ROUTE_RENDER_WAIT);
		await userEvent.click(screen.getByRole("button", { name: "Reset" }));

		await waitFor(() => expect(values(resultUrls.at(-1), "outcome")).toStrictEqual([]));
		expect(resultUrls.at(-1)?.searchParams.get("developerId")).toBe("7");
		expect(router.state.location.search).toMatchObject({ developerId: "7" });

		await userEvent.click(screen.getByRole("button", { name: "Clear scope" }));
		await waitFor(() => expect(resultUrls.at(-1)?.searchParams.get("developerId")).toBeNull());
	});

	/**
	 * The list loads the next page at its end, under the same filters: a next page that lost a filter
	 * would append rows that do not match it. The observer never fires here, so the press is what asks.
	 */
	it("asks for the next page of reviews under the same filters, and shows each review once", async () => {
		vi.stubGlobal("IntersectionObserver", ObserverStub);
		const { reviewUrls } = recordRequests();
		serveReviewPages(reviewUrls);

		renderRouteAtWithRouter('/w/acme/admin/practices/reviews?status=["COMPLETE"]');
		await screen.findByRole("link", { name: reviewLinkName(FIRST_PAGE) }, ROUTE_RENDER_WAIT);
		await userEvent.click(screen.getByRole("button", { name: "Show more reviews" }));
		await screen.findByRole("link", { name: reviewLinkName(SECOND_PAGE) }, ROUTE_RENDER_WAIT);

		const next = reviewUrls.at(-1);
		expect(next?.searchParams.get("page")).toBe("1");
		expect(next?.searchParams.get("size")).toBe("25");
		expect(values(next, "status")).toStrictEqual(["COMPLETE"]);
		expect(screen.getAllByRole("link", { name: reviewLinkName(FIRST_PAGE) })).toHaveLength(1);
		// The last page is in, so the list ends without a button.
		expect(screen.queryByRole("button", { name: "Show more reviews" })).toBeNull();
	});

	/**
	 * These endpoints answer with a flat Spring page. A list that read the paging from anywhere else
	 * would stop after its first page and show no total, which hides most of the history.
	 */
	it.each([
		["results", "observations", reviewResults, "3 observations."],
		["problems", "problems", reviewProblems, "3 problems."],
		["feedback", "feedback", reviewFeedback, "3 pieces of feedback."],
	] as const)(
		"shows the %s total and loads the second page",
		async (tab, noun, fixtureRows, total) => {
			vi.stubGlobal("IntersectionObserver", ObserverStub);
			recordRequests();
			const pageReads = serveTwoFlatPages(tab, fixtureRows);

			renderRouteAtWithRouter(`/w/acme/admin/practices/reviews/${tab}`);
			await screen.findByText(total, undefined, ROUTE_RENDER_WAIT);
			const rowsBefore = screen.getAllByRole("row").length;
			await userEvent.click(screen.getByRole("button", { name: `Show more ${noun}` }));

			await waitFor(() => expect(screen.getAllByRole("row").length).toBeGreaterThan(rowsBefore));
			expect(pageReads).toStrictEqual(["0", "1"]);
			expect(screen.queryByRole("button", { name: `Show more ${noun}` })).toBeNull();
		},
	);

	it("names who asked for a review and its failure on its row, and opens its sheet", async () => {
		server.use(...reviewHandlers());
		const requestedReview: ReviewSummary = {
			...FIRST_PAGE,
			origin: "REQUEST",
			requestedBy: reviewAccounts.grace,
			status: "FAILED",
			stopReason: "TIMED_OUT",
		};
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/reviews", () =>
				HttpResponse.json(pageOf([requestedReview])),
			),
		);

		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices/reviews");
		await screen.findByRole("link", { name: reviewLinkName(requestedReview) }, ROUTE_RENDER_WAIT);
		const table = screen.getByRole("table", { name: "Practice reviews, newest first" });
		const requester = reviewAccounts.grace.displayName;
		assert(requester !== null);
		await within(table).findByText(requester, undefined, ROUTE_RENDER_WAIT);
		within(table).getByRole("columnheader", { name: "Started by" });
		expect(within(table).getAllByText("Failed").length).toBeGreaterThan(0);
		await userEvent.click(
			within(table).getByRole("link", { name: reviewLinkName(requestedReview) }),
		);

		await waitFor(() => {
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
				`review:${requestedReview.id}`,
			]);
		});
		expect(router.state.location.pathname).toBe("/w/acme/admin/practices/reviews");
	});
});
