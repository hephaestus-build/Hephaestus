import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { InAppFeedback } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { clearUserView } from "@/runtime/user-view/session";
import { detailObservation, detailRun } from "@/stories/practice-detail-story-mock-data";
import {
	groups,
	groupStandings,
	OVERVIEW_FIXTURE,
	packagingGroup,
	practiceStandings,
} from "@/stories/practice-profile-story-mock-data";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";
import { storeUserView } from "@/test/user-view";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: 15_000 });

const PAGE = "/w/acme/practice-profile";

const [first] = practiceStandings;
if (!first) {
	throw new Error("The fixtures carry at least one practice with a standing");
}
/** Narrowed once, so the helpers below can read it without a guard each. */
const practice = first;

/** One review of the first practice, so its level has an observation to show. */
const observation = {
	...detailObservation,
	practiceSlug: practice.slug,
	practiceName: practice.name,
};
const run = { ...detailRun, observations: [observation] };

beforeEach(() => {
	server.use(
		// The surface exists only where practices review the work, and the shared fixture has them
		// off, so every case below has to say that this workspace reviews.
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
		// A plain MEMBER: the profile is the developer's own page, not an admin surface.
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json(groups)),
		http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () =>
			HttpResponse.json(Object.values(groupStandings)),
		),
		http.get("*/workspaces/:workspaceSlug/practices/standings", () =>
			HttpResponse.json(practiceStandings),
		),
		http.get("*/workspaces/:workspaceSlug/practice-profile/overview", () =>
			HttpResponse.json(OVERVIEW_FIXTURE),
		),
		http.get("*/workspaces/:workspaceSlug/practices/feedback/in-app", () => HttpResponse.json([])),
		// The practice level's one query, answered as soon as it opens; it carries every
		// observation in full, so opening one asks for nothing more.
		http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/review-runs", () =>
			HttpResponse.json({ content: [run], hasNext: false, page: 0, size: 10 }),
		),
	);
});

async function renderProfile(path = PAGE) {
	const { router } = renderRouteAtWithRouter(path);
	await screen.findByRole("heading", { level: 1, name: "Practice profile" }, ROUTE_RENDER_WAIT);
	return router;
}

const group = `practice-group:${packagingGroup.slug}`;
const practiceEntry = `practice:${practice.slug}`;

/** Through the page to the first practice's level, one level per history entry. */
async function openPractice(router: Awaited<ReturnType<typeof renderProfile>>) {
	fireEvent.click(
		await screen.findByRole("link", { name: "See all practice groups" }, ROUTE_RENDER_WAIT),
	);
	fireEvent.click(
		await screen.findByRole(
			"button",
			{ name: `Open group ${packagingGroup.name}` },
			ROUTE_RENDER_WAIT,
		),
	);
	fireEvent.click(
		await screen.findByRole(
			"button",
			{ name: `Open practice ${practice.name}` },
			ROUTE_RENDER_WAIT,
		),
	);
	await waitFor(() =>
		expect(router.state.location.search.detail).toStrictEqual([
			"practice-groups:all",
			group,
			practiceEntry,
		]),
	);
}

/**
 * What the route owns and no story can see: how the page's controls are spelled in the URL. The
 * drawer's levels are addressed by the `detail` stack, and the page's tab and the table's sort are
 * silent at their defaults so the address a reader shares is the shortest one that means the same.
 */
describe("practice profile route", () => {
	it("sends a reader away when this workspace does not review practices, without delivering feedback", async () => {
		const inApp = vi.fn(() => HttpResponse.json([]));
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { practicesEnabled: false })]),
			),
			http.get("*/workspaces/:workspaceSlug/practices/feedback/in-app", inApp),
		);
		const { router } = renderRouteAtWithRouter(PAGE);

		// With practices off the page does not exist here, so the reader lands on the workspace home
		// rather than on a profile with nothing to be about.
		await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme"), ROUTE_RENDER_WAIT);
		// Reading the cards is what delivers them: a reader who is sent away must never have their
		// unread feedback marked delivered on the way out.
		expect(inApp).not.toHaveBeenCalled();
	});

	it("stacks the list, a group and a practice in the detail param, one history entry each", async () => {
		const router = await renderProfile();
		const entries = router.history.length;

		const detail = () => router.state.location.search.detail;

		fireEvent.click(
			await screen.findByRole("link", { name: "See all practice groups" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => expect(detail()).toStrictEqual(["practice-groups:all"]));
		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: `Open group ${packagingGroup.name}` },
				ROUTE_RENDER_WAIT,
			),
		);
		await waitFor(() => expect(detail()).toStrictEqual(["practice-groups:all", group]));
		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: `Open practice ${practice.name}` },
				ROUTE_RENDER_WAIT,
			),
		);

		await waitFor(() =>
			expect(detail()).toStrictEqual(["practice-groups:all", group, practiceEntry]),
		);
		// The list, the group and the practice: three levels, and Back pops exactly one.
		expect(router.history).toHaveLength(entries + 3);
		router.history.back();
		await waitFor(() => expect(detail()).toStrictEqual(["practice-groups:all", group]));
	});

	it("opens and closes an observation without writing the URL, so leaving the level takes one step", async () => {
		const router = await renderProfile();
		await openPractice(router);
		const entries = router.history.length;
		const searchStr = () => router.state.location.searchStr;
		const before = searchStr();

		// The observation arrives open, with what the feed carries; a press closes it in place.
		const rowName = { name: new RegExp(observation.summary, "u") };
		const row = () => screen.getByRole("button", rowName);
		// The feed is its own request: under load it lands after the level does.
		await screen.findByRole("button", rowName, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(row().getAttribute("aria-expanded")).toBe("true"));
		await screen.findByText("Why it was noted");
		fireEvent.click(row());
		await waitFor(() => expect(row().getAttribute("aria-expanded")).toBe("false"));
		fireEvent.click(row());
		await waitFor(() => expect(row().getAttribute("aria-expanded")).toBe("true"));
		// Neither press is a navigation: the address and the history are as they were.
		expect(searchStr()).toBe(before);
		expect(router.history).toHaveLength(entries);

		// The level was pushed on this visit, so its Back goes back in history — to the group.
		fireEvent.click(screen.getByRole("button", { name: "Back" }));
		await waitFor(() =>
			expect(router.state.location.search.detail).toStrictEqual(["practice-groups:all", group]),
		);
	});

	it("clears the selection with the level when the level is closed forward", async () => {
		// Arrived by address: nothing behind the level to go back to, so closing writes a new one.
		// The page under the open drawer is hidden from the accessibility tree, so the level's own
		// heading is what says the route has rendered.
		const { router } = renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify([group, practiceEntry]))}&practiceTab=about`,
		);
		await screen.findByRole("heading", { name: practice.name }, ROUTE_RENDER_WAIT);
		const search = () => router.state.location.search;
		expect(search().practiceTab).toBe("about");

		fireEvent.click(screen.getByRole("button", { name: "Back" }));

		await waitFor(() => expect(search().detail).toStrictEqual([group]));
		expect(router.state.location.searchStr).not.toContain("practiceTab");
	});

	it("keeps the URL silent on the default feedback tab and spells every other one", async () => {
		const router = await renderProfile();

		fireEvent.click(await screen.findByRole("tab", { name: /^Resolved/u }));
		await waitFor(() => expect(router.state.location.search.feedback).toBe("resolved"));
		// The tab is selected once the page has read it back from the URL; a press on a tab that is
		// still selected in the DOM is not a change.
		fireEvent.click(await screen.findByRole("tab", { name: /^Newest/u, selected: false }));

		await waitFor(() => expect(router.state.location.searchStr).toBe(""));
	});

	it("keeps the URL silent on the default sort direction, and the level a press from dismissed", async () => {
		const router = await renderProfile(`${PAGE}?dir=asc`);

		fireEvent.click(
			await screen.findByRole("link", { name: "See all practice groups" }, ROUTE_RENDER_WAIT),
		);
		const entries = router.history.length;
		const table = await screen.findByRole("table", { name: "All practice groups" });
		const standing = () => within(table).getByRole("button", { name: /Standing/u });
		// One press flips the sort away from the default, the next lands back on it — once the
		// header has read the first press back from the URL.
		fireEvent.click(standing());
		await waitFor(() => expect(router.state.location.search.dir).toBe("desc"));
		await waitFor(() =>
			expect(standing().closest("th")?.getAttribute("aria-sort")).toBe("descending"),
		);
		fireEvent.click(standing());

		await waitFor(() =>
			expect(standing().closest("th")?.getAttribute("aria-sort")).toBe("ascending"),
		);
		expect(router.state.location.searchStr).not.toContain("dir=");
		// Sorting is a view of the open level, not a place: neither press left a history entry, and
		// the level was pushed on this one, so Back still dismisses it in a single step.
		expect(router.history).toHaveLength(entries);
		router.history.back();
		await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
	});
});

/** One piece of feedback on the page, about the practice the level opens. */
const feedback: Wire<InAppFeedback> = {
	id: "00000000-0000-0000-0000-000000000201",
	headline: "Pull requests bundle a fix with a refactor",
	body: "Reviewers had to follow two intentions in one diff.",
	practiceSlug: practice.slug,
	practiceName: practice.name,
	groupSlug: packagingGroup.slug,
	groupName: packagingGroup.name,
	preparedAt: "2026-09-09T14:10:00Z",
	cleanNeeded: 3,
	cleanWork: [],
	evidence: [],
};

/**
 * A user view is an administrator reading the developer's page: everything the developer would
 * answer — a rating, a comment, the card's own answer, a response to an observation — is theirs
 * alone, so none of it is
 * offered. The developer's own visit is the control that shows the same controls are there.
 */
describe("practice profile in a user view", () => {
	beforeEach(() => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/feedback/in-app", () =>
				HttpResponse.json([feedback]),
			),
		);
	});
	afterEach(clearUserView);

	/**
	 * The card's rating and answer buttons on the page, then the observation's response on the
	 * practice level — the one of the two that offers a dispute of its own.
	 */
	async function responseControls() {
		const router = await renderProfile();
		await screen.findByText(feedback.headline, undefined, ROUTE_RENDER_WAIT);
		const ratings = screen.queryAllByRole("button", { name: "Helpful" }).length;
		const answers = screen.queryAllByRole("button", { name: "Addressed" }).length;
		await openPractice(router);
		await screen.findByText("Why it was noted", undefined, ROUTE_RENDER_WAIT);
		return {
			ratings,
			answers,
			observationResponse: screen.queryAllByRole("button", { name: "Disputed" }).length,
		};
	}

	it("offers the developer a rating, an answer and a response", async () => {
		await expect(responseControls()).resolves.toStrictEqual({
			ratings: 1,
			answers: 1,
			observationResponse: 1,
		});
	});

	it("offers an administrator viewing as the developer none of them", async () => {
		storeUserView({ workspaceSlug: "acme", login: "ada", name: "Ada" });
		await expect(responseControls()).resolves.toStrictEqual({
			ratings: 0,
			answers: 0,
			observationResponse: 0,
		});
	});
});
