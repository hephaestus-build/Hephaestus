import { screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { reviewHandlers } from "@/components/admin/practice-reviews/story-mock-server";
import { server } from "@/mocks/server";
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
	agentJobId: "3f2b6c1e-8a4d-4f0b-9c7e-2d5a1b0c9e84",
	artifactKind: "scm.pull_request",
	artifactId: "42",
	from: "2026-09-01",
	to: "2026-09-10",
};
const SCOPE_QUERY = new URLSearchParams(SCOPE).toString();

beforeEach(() => {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
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
	return Object.fromEntries(new URL(link.href).searchParams);
}

function sectionLink(navigation: HTMLElement, name: string) {
	return within(navigation).getByRole<HTMLAnchorElement>("link", { name, hidden: true });
}

describe("practice review routes", () => {
	it.each([
		["Overview", REVIEWS],
		["Reviews", `${REVIEWS}/runs`],
		["Observations", `${REVIEWS}/observations`],
		["Feedback", `${REVIEWS}/feedback`],
		// A level is opened over the tab the reader is on, and never moves them off it.
		["Observations", `${REVIEWS}/observations?detail=feedback:${FEEDBACK}`],
	])("marks only %s as current on %s", async (expectedCurrent, url) => {
		renderRouteAtWithRouter(url);

		const navigation = await sectionNavigation();
		const currentLinks = within(navigation).getAllByRole("link", { current: "page", hidden: true });
		expect(currentLinks).toHaveLength(1);
		within(navigation).getByRole("link", { name: expectedCurrent, current: "page", hidden: true });
	});

	/**
	 * "What did this review say" and "what became of it" are one switch apart: the two record lists
	 * hand each other what they are narrowed to, and the overview and the review list take none of it.
	 */
	it.each([
		["observations", "Feedback", "feedback"],
		["feedback", "Observations", "observations"],
	])("carries the %s list's scope into the %s tab", async (fromPath, to, toPath) => {
		renderRouteAtWithRouter(`${REVIEWS}/${fromPath}?${SCOPE_QUERY}`);

		const navigation = await sectionNavigation();
		const target = sectionLink(navigation, to);
		expect(new URL(target.href).pathname).toBe(`${REVIEWS}/${toPath}`);
		expect(carriedSearch(target)).toStrictEqual(SCOPE);

		for (const unscoped of ["Overview", "Reviews"]) {
			expect(carriedSearch(sectionLink(navigation, unscoped))).toStrictEqual({});
		}
	});

	/**
	 * The range is the overview's: a list has its own dates, so a range chosen on the overview does
	 * not follow the reader to a list tab — and neither back to the overview from one.
	 */
	it("keeps a chosen range on the overview and off every tab", async () => {
		renderRouteAtWithRouter(`${REVIEWS}?range=90d`);
		const navigation = await sectionNavigation();
		for (const tab of ["Overview", "Reviews", "Observations", "Feedback"]) {
			expect(carriedSearch(sectionLink(navigation, tab))).toStrictEqual({});
		}
	});

	/** A practice level counts over the overview's range, so opening one keeps it. */
	it("keeps the chosen range on a practice level opened from the overview", async () => {
		renderRouteAtWithRouter(`${REVIEWS}?range=90d`);
		const practice = await screen.findByRole<HTMLAnchorElement>(
			"link",
			{ name: "Thin controllers" },
			ROUTE_RENDER_WAIT,
		);
		expect(carriedSearch(practice)).toMatchObject({ range: "90d" });
	});
});

describe("practice review page titles", () => {
	it.each([
		["the tab, with nothing open", REVIEWS, "Practice reviews · Admin · Hephaestus"],
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
});
