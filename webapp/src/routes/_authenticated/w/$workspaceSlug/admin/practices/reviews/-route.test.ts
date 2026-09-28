import { screen, within } from "@testing-library/react";
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

	it("carries a chosen range to every tab", async () => {
		renderRouteAtWithRouter(`${REVIEWS}/runs?range=90d`);
		const chosen = await sectionNavigation();
		for (const tab of ["Overview", "Reviews", "Observations", "Feedback"]) {
			expect(carriedSearch(sectionLink(chosen, tab))).toStrictEqual({ range: "90d" });
		}
	});

	it("leaves the default range out of every tab's address", async () => {
		renderRouteAtWithRouter(`${REVIEWS}/runs`);
		const navigation = await sectionNavigation();
		for (const tab of ["Overview", "Reviews", "Observations", "Feedback"]) {
			expect(carriedSearch(sectionLink(navigation, tab))).toStrictEqual({});
		}
	});
});
