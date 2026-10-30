import { screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { resultDetail } from "@/components/admin/practice-reviews/fixtures";
import { parseSearch } from "@/lib/router-search";
import { server } from "@/mocks/server";
import { reviewHandlers } from "@/routes/_authenticated/w/$workspaceSlug/admin/practices/reviews/-reviews-server";
import { deferred } from "@/test/async";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

const FEEDBACK = "dddddddd-2222-2222-2222-222222222222";

const REVIEWS = "/w/acme/admin/practices/reviews";

/**
 * Every scope param the two record lists share, set at once, so a dropped one cannot hide. The
 * review id is a real version-4 UUID: the lists' schema drops one that is not, as the fixtures' are.
 */
const SCOPE = {
	reviewId: "3f2b6c1e-8a4d-4f0b-9c7e-2d5a1b0c9e84",
	resultId: "9a1d3c5e-7b2f-4e6a-8c0d-1f3b5d7e9a2c",
	itemKind: "scm.pull_request",
	itemId: "42",
	from: "2026-09-01",
	to: "2026-09-10",
};
const SCOPE_QUERY = new URLSearchParams(SCOPE).toString();

beforeEach(() => {
	// jsdom needs an IntersectionObserver stub for paged lists.
	vi.stubGlobal("IntersectionObserver", ObserverStub);
	server.use(
		http.get(`*/workspaces/:workspaceSlug/practices/reviews/results/${SCOPE.resultId}`, () =>
			HttpResponse.json({
				...resultDetail("55555555-5555-5555-5555-555555555555"),
				id: SCOPE.resultId,
				reviewId: SCOPE.reviewId,
			}),
		),
		...reviewHandlers(),
	);
});

/**
 * An open level is modal, so the page under it leaves the accessibility tree while it is open; the
 * tabs are still the tabs the reader returns to, which is what is asserted here.
 */
async function sectionNavigation() {
	return screen.findByRole(
		"navigation",
		{ name: "Practice review sections", hidden: true },
		ROUTE_RENDER_WAIT,
	);
}

function carriedSearch(link: HTMLAnchorElement) {
	return parseSearch(new URL(link.href).search);
}

/** A section's name, with or without the count a list tab carries once it is known: "Reviews, 3". */
function sectionName(name: string): RegExp {
	return new RegExp(`^${name}(, [\\d,]+)?$`, "u");
}

function sectionLink(navigation: HTMLElement, name: string) {
	return within(navigation).getByRole<HTMLAnchorElement>("link", {
		name: sectionName(name),
		hidden: true,
	});
}

function countPage(total: number) {
	return {
		content: [],
		empty: true,
		first: true,
		last: true,
		number: 0,
		numberOfElements: 0,
		size: 1,
		totalElements: total,
		totalPages: total,
	};
}

/**
 * A list endpoint that answers its one-row count reads with `total` and records them; a list's own
 * read falls through to the fixture's handler.
 */
function countRead(path: string, total: number, counted: URL[]) {
	return http.get(`*/workspaces/:workspaceSlug${path}`, ({ request }) => {
		const url = new URL(request.url);
		if (url.searchParams.get("size") !== "1") {
			return;
		}
		counted.push(url);
		return HttpResponse.json(countPage(total));
	});
}

function failingOnceCount(total: number) {
	let reads = 0;
	const handler = http.get(
		"*/workspaces/:workspaceSlug/practices/reviews/results",
		({ request }) => {
			if (new URL(request.url).searchParams.get("size") !== "1") {
				return;
			}
			reads += 1;
			return reads === 1
				? HttpResponse.json({ status: 503 }, { status: 503 })
				: HttpResponse.json(countPage(total));
		},
	);
	return { handler, count: () => reads };
}

function heldScopedCount() {
	const held = deferred();
	let asked = false;
	const handler = http.get(
		"*/workspaces/:workspaceSlug/practices/reviews/results",
		async ({ request }) => {
			const url = new URL(request.url);
			if (url.searchParams.get("size") !== "1" || url.searchParams.get("developerId") !== "7") {
				return;
			}
			asked = true;
			await held.promise;
			return HttpResponse.json(countPage(42));
		},
	);
	return { handler, release: () => held.resolve(), asked: () => asked };
}

describe("practice review routes", () => {
	it.each([
		["Overview", `${REVIEWS}/overview`],
		["Reviews", REVIEWS],
		["Observations", `${REVIEWS}/results`],
		["Problems", `${REVIEWS}/problems`],
		["Feedback", `${REVIEWS}/feedback`],
		// A level is opened over the tab the reader is on, and never moves them off it.
		["Observations", `${REVIEWS}/results?detail=feedback:${FEEDBACK}`],
	])("marks only %s as current on %s", async (expectedCurrent, url) => {
		renderRouteAtWithRouter(url);

		const navigation = await sectionNavigation();
		const currentLinks = within(navigation).getAllByRole("link", { current: "page", hidden: true });
		expect(currentLinks).toHaveLength(1);
		within(navigation).getByRole("link", {
			name: sectionName(expectedCurrent),
			current: "page",
			hidden: true,
		});
	});

	it.each([
		["/results", `developerId=7&severity=${encodeURIComponent('["MAJOR"]')}`],
		["/problems", `developerId=7&severity=${encodeURIComponent('["MAJOR"]')}`],
		["/feedback", `developerId=7&location=GITHUB`],
		["", `developerId=7&status=${encodeURIComponent('["FAILED"]')}`],
		["/overview", "developerId=7"],
	])('carries the scope from "%s" to every tab', async (fromPath, own) => {
		const scope = `${SCOPE_QUERY}&practiceSlug=${encodeURIComponent('["thin-controllers"]')}&groupSlug=${encodeURIComponent('["design"]')}`;
		renderRouteAtWithRouter(`${REVIEWS}${fromPath}?${scope}&${own}`);

		const navigation = await sectionNavigation();
		// A list of one is written as its one value.
		const expected = {
			...SCOPE,
			practiceSlug: "thin-controllers",
			groupSlug: "design",
			developerId: "7",
		};
		for (const tab of ["Overview", "Reviews", "Observations", "Problems", "Feedback"]) {
			expect(carriedSearch(sectionLink(navigation, tab))).toStrictEqual(expected);
		}
	});

	it("carries nothing when nothing is scoped", async () => {
		renderRouteAtWithRouter(`${REVIEWS}/results?severity=${encodeURIComponent('["MAJOR"]')}`);
		const navigation = await sectionNavigation();
		for (const tab of ["Overview", "Reviews", "Observations", "Problems", "Feedback"]) {
			expect(carriedSearch(sectionLink(navigation, tab))).toStrictEqual({});
		}
	});

	/**
	 * The range is the overview's: a list has its own dates, so a range chosen on the overview does
	 * not follow the reader to a list tab — and neither back to the overview from one.
	 */
	it("keeps a chosen range on the overview and off every tab", async () => {
		renderRouteAtWithRouter(`${REVIEWS}/overview?range=90d`);
		const navigation = await sectionNavigation();
		for (const tab of ["Overview", "Reviews", "Observations", "Problems", "Feedback"]) {
			expect(carriedSearch(sectionLink(navigation, tab))).toStrictEqual({});
		}
	});

	it("opens a practice's sheet from the overview, over the overview's range", async () => {
		renderRouteAtWithRouter(`${REVIEWS}/overview?range=90d`);
		const practice = await screen.findByRole<HTMLAnchorElement>(
			"link",
			{ name: "Open practice Thin controllers" },
			ROUTE_RENDER_WAIT,
		);
		expect(new URL(practice.href).pathname).toBe(`${REVIEWS}/overview`);
		expect(carriedSearch(practice)).toMatchObject({
			detail: "practice:thin-controllers",
			range: "90d",
		});
	});
});

describe("practice review page titles", () => {
	it.each([
		["the tab, with nothing open", REVIEWS, "Reviews · Admin · Hephaestus"],
		["the overview tab", `${REVIEWS}/overview`, "Overview · Admin · Hephaestus"],
		[
			"the record open in front",
			`${REVIEWS}?detail=feedback:${FEEDBACK}`,
			"Feedback · Practice reviews · Admin · Hephaestus",
		],
	])("names %s", async (_, url, title) => {
		const { router } = renderRouteAtWithRouter(url);
		await sectionNavigation();
		await waitFor(() =>
			expect(
				router.state.matches
					.flatMap((match) => match.meta)
					.reverse()
					.find((meta) => meta !== undefined),
			).toStrictEqual({ title }),
		);
	});

	/**
	 * Each list tab says how many it holds under the shared scope, read as the total of a one-row page
	 * of its own endpoint: the developer, practices, groups, dates and tracked record apply, a tab's own
	 * facets do not, and the overview has no count.
	 */
	it("counts each list tab under the shared scope, not under a tab's own facets", async () => {
		const counted: URL[] = [];
		server.use(
			countRead("/practices/reviews", 1688, counted),
			countRead("/practices/reviews/results", 13_308, counted),
			countRead("/practices/reviews/problems", 2210, counted),
			countRead("/practices/reviews/feedback", 1951, counted),
		);
		renderRouteAtWithRouter(
			`${REVIEWS}?developerId=7&groupSlug=${encodeURIComponent('["design"]')}&status=${encodeURIComponent('["FAILED"]')}`,
		);
		const navigation = await sectionNavigation();
		await within(navigation).findByRole(
			"link",
			{ name: "Reviews, 1,688", hidden: true },
			ROUTE_RENDER_WAIT,
		);
		await within(navigation).findByRole("link", { name: "Observations, 13,308", hidden: true });
		await within(navigation).findByRole("link", { name: "Problems, 2,210", hidden: true });
		await within(navigation).findByRole("link", { name: "Feedback, 1,951", hidden: true });
		within(navigation).getByRole("link", { name: "Overview", hidden: true });

		const reviews = counted.find((url) => url.pathname.endsWith("/practices/reviews"));
		expect(reviews?.searchParams.get("developerId")).toBe("7");
		expect(reviews?.searchParams.getAll("groupSlug").join(",")).toContain("design");
		// The Reviews tab's own Status facet narrows its table, not its count.
		expect(reviews?.searchParams.has("status")).toBe(false);
		const feedback = counted.find((url) => url.pathname.endsWith("/feedback"));
		expect(feedback?.searchParams.get("developerId")).toBe("7");
	});

	// Failed counts need their own retry; reloading the page must not be the only recovery.
	it("asks again for a count that failed, and shows it once it is known", async () => {
		const reads = failingOnceCount(13_308);
		server.use(reads.handler);
		renderRouteAtWithRouter(REVIEWS);
		const navigation = await sectionNavigation();
		await within(navigation).findByRole(
			"link",
			{ name: "Observations, 13,308", hidden: true },
			{ timeout: 10_000 },
		);
		expect(reads.count()).toBe(2);
	});

	/** A scope change must not attribute the previous total to a new person while its read is held. */
	it("hides the previous scope's count until the new one is known", async () => {
		const scoped = heldScopedCount();
		server.use(scoped.handler, countRead("/practices/reviews/results", 13_308, []));
		try {
			const { router } = renderRouteAtWithRouter(REVIEWS);
			const navigation = await sectionNavigation();
			await within(navigation).findByRole(
				"link",
				{ name: "Observations, 13,308", hidden: true },
				ROUTE_RENDER_WAIT,
			);
			await router.navigate({
				to: "/w/$workspaceSlug/admin/practices/reviews",
				params: { workspaceSlug: "acme" },
				search: { developerId: "7" },
			});
			await waitFor(() => expect(scoped.asked()).toBe(true));
			expect(
				within(navigation).queryByRole("link", { name: "Observations, 13,308", hidden: true }),
			).toBeNull();
			within(navigation).getByRole("link", { name: "Observations", hidden: true });
			scoped.release();
			await within(navigation).findByRole("link", { name: "Observations, 42", hidden: true });
		} finally {
			scoped.release();
		}
	});
});
