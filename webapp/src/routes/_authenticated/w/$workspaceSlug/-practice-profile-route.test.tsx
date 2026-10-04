import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { InAppFeedback, ProfileReviewRun } from "@/api/types.gen";
import { rangeStart } from "@/components/activity/activity-range";
import { ACTIVE_REVIEW_POLL_MS } from "@/components/admin/practice-reviews/review-search";
import { artifactTrace } from "@/components/practice-trace/fixtures";
import { formatDayTime, type Wire } from "@/lib/dates";
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
import {
	openProfileReviewRun,
	profileReviewRuns,
} from "@/stories/profile-review-runs-story-mock-data";
import { STORY_NOW } from "@/stories/story-clock";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";
import { storeUserView } from "@/test/user-view";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: ROUTE_RENDER_WAIT.timeout });

const PAGE = "/w/acme/practice-profile";

/** Long enough for a response to render once the route is up, short of the test's own timeout. */
const SETTLE_WAIT = { timeout: 5000 } as const;

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

/** The review before the open one, on the same merge request. */
const [, earlierOnSameWork] = profileReviewRuns;
if (earlierOnSameWork?.reviewedWork !== openProfileReviewRun.reviewedWork) {
	throw new Error("The fixtures carry a second review of the open review's work");
}

/** The `reviewId` each read of the work's review activity carried, newest last. */
let tracedReviewIds: (string | null)[] = [];

beforeEach(() => {
	tracedReviewIds = [];
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
		http.get("*/workspaces/:workspaceSlug/practice-profile/review-runs", () =>
			HttpResponse.json({ content: profileReviewRuns, hasNext: false, page: 0, size: 10 }),
		),
		http.get("*/workspaces/:workspaceSlug/practice-profile/review-runs/:reviewId", () =>
			HttpResponse.json({ run: openProfileReviewRun, observations: [observation] }),
		),
		http.get(
			"*/workspaces/:workspaceSlug/practices/trace/:artifactKind/:artifactId",
			({ request }) => {
				tracedReviewIds.push(new URL(request.url).searchParams.get("reviewId"));
				return HttpResponse.json(artifactTrace);
			},
		),
	);
});

/** The open review's row link, named for its work and its time. */
const openReviewRowName = `Open review of ${openProfileReviewRun.reviewedWork.label}, ${formatDayTime(openProfileReviewRun.reviewedAt, new Date(STORY_NOW))}`;

async function renderProfile(path = PAGE) {
	const { router } = renderRouteAtWithRouter(path);
	await screen.findByRole("heading", { level: 1, name: "Practice profile" }, ROUTE_RENDER_WAIT);
	return router;
}

const openReviewsChip = async () =>
	screen.findByRole("link", { name: /^Latest review/u }, ROUTE_RENDER_WAIT);

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

		// With practices off the page does not exist here, so the reader lands on the workspace home,
		// which is Activity for such a workspace, rather than on a profile with nothing to be about.
		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/activity"),
			ROUTE_RENDER_WAIT,
		);
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

	it("opens every review of the reader's work from the chip, then one review over it", async () => {
		const router = await renderProfile();
		const detail = () => router.state.location.search.detail;

		fireEvent.click(await openReviewsChip());
		await waitFor(() => expect(detail()).toStrictEqual(["reviews:all"]));
		await screen.findByRole("heading", { name: "Reviews of your work" }, ROUTE_RENDER_WAIT);

		fireEvent.click(
			await screen.findByRole("button", { name: openReviewRowName }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() =>
			expect(detail()).toStrictEqual(["reviews:all", `review:${openProfileReviewRun.reviewId}`]),
		);
		await screen.findByRole("tab", { name: /^Every practice/u }, ROUTE_RENDER_WAIT);
		// A quiet practice's reason comes only from the activity, asked for this review.
		await screen.findByText(
			"The review ended before it reached this practice.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		// The row under the level and the head over it.
		await waitFor(() => expect(screen.getAllByText("2nd review")).toHaveLength(2));
		await waitFor(() => expect(tracedReviewIds).toContain(openProfileReviewRun.reviewId));

		// One level per history entry, so Back closes exactly one and then the drawer.
		router.history.back();
		await waitFor(() => expect(detail()).toStrictEqual(["reviews:all"]));
		router.history.back();
		await waitFor(() => expect(detail()).toBeUndefined());
	});

	it("turns the chosen timeframe into the since the reviews list asks for", async () => {
		// Fixed at noon, so the day the bound is counted from cannot turn during the test.
		const noon = new Date(2026, 8, 22, 12, 0);
		vi.useFakeTimers({ toFake: ["Date"] });
		vi.setSystemTime(noon);
		try {
			const asked: (string | null)[] = [];
			server.use(
				http.get("*/workspaces/:workspaceSlug/practice-profile/review-runs", ({ request }) => {
					asked.push(new URL(request.url).searchParams.get("since"));
					return HttpResponse.json({
						content: profileReviewRuns,
						hasNext: false,
						page: 0,
						size: 10,
					});
				}),
			);
			const router = await renderProfile();

			fireEvent.click(await openReviewsChip());
			await screen.findByRole("heading", { name: "Reviews of your work" }, ROUTE_RENDER_WAIT);
			await waitFor(() => expect(asked[0]).toBeNull());

			const user = userEvent.setup();
			await user.click(screen.getByRole("combobox", { name: "Timeframe" }));
			await user.click(await screen.findByRole("option", { name: "Last 30 days" }));

			await waitFor(() => expect(router.state.location.search.reviewSince).toBe("30d"));
			await waitFor(() =>
				expect(asked.at(-1)).toBe(rangeStart(noon.getTime(), "30d").toISOString()),
			);
		} finally {
			vi.useRealTimers();
		}
	});

	it("says why a review asked for from a row was refused", async () => {
		server.use(
			http.post("*/workspaces/:workspaceSlug/practices/review-requests", () =>
				HttpResponse.json({
					status: "REFUSED",
					reason: "REQUEST_COOLDOWN_ACTIVE",
					reasonDescription: "A review of this was already asked for a moment ago.",
				}),
			),
		);
		await renderProfile();
		fireEvent.click(await openReviewsChip());

		fireEvent.click(
			await screen.findByRole("button", { name: "Request review: #890" }, ROUTE_RENDER_WAIT),
		);

		await screen.findByText("No review was started", undefined, ROUTE_RENDER_WAIT);
		await screen.findByText("A review of this was already asked for a moment ago.");
	});

	it("says a refusal on the review it was asked from, on no other review of the work, and not again on a later visit", async () => {
		server.use(
			http.post("*/workspaces/:workspaceSlug/practices/review-requests", () =>
				HttpResponse.json({
					status: "REFUSED",
					reason: "REQUEST_COOLDOWN_ACTIVE",
					reasonDescription: "A review of this was already asked for a moment ago.",
				}),
			),
			http.get("*/workspaces/:workspaceSlug/practice-profile/review-runs/:reviewId", ({ params }) =>
				HttpResponse.json({
					run: profileReviewRuns.find((candidate) => candidate.reviewId === params.reviewId),
					observations: [],
				}),
			),
		);
		// The page is inert under the drawer, so the head's own button is what says it has rendered.
		const { router } = renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(
				JSON.stringify(["reviews:all", `review:${openProfileReviewRun.reviewId}`]),
			)}`,
		);

		fireEvent.click(
			await screen.findByRole("button", { name: "Request review" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByText("A review of this was already asked for a moment ago.");

		// Another review of the same work, opened by its address.
		router.history.push(
			`${PAGE}?detail=${encodeURIComponent(
				JSON.stringify(["reviews:all", `review:${earlierOnSameWork.reviewId}`]),
			)}`,
		);
		await waitFor(() => expect(tracedReviewIds).toContain(earlierOnSameWork.reviewId));
		await screen.findByRole("tab", { name: /^Every practice/u }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("No review was started")).toBeNull();

		// Back on the review it was asked from, the refusal was answered on the earlier visit.
		router.history.back();
		await waitFor(() =>
			expect(router.state.location.search.detail).toStrictEqual([
				"reviews:all",
				`review:${openProfileReviewRun.reviewId}`,
			]),
		);
		await screen.findByRole("tab", { name: /^Every practice/u }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("No review was started")).toBeNull();
	});

	it("reads the work's review activity and the list once more when the open review stops running", async () => {
		// Only the polls are faked, and the clock moves only when the test moves it: every interval
		// armed while the page loads is armed at the same instant, and the waits stay on real time.
		vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
		try {
			let status: NonNullable<ProfileReviewRun["status"]> = "IN_PROGRESS";
			// Every read of the activity is this review's; the first is held until the test answers it.
			let activityReads = 0;
			let listReads = 0;
			const firstActivity = deferred();
			const held = [firstActivity.promise];
			server.use(
				http.get("*/workspaces/:workspaceSlug/practice-profile/review-runs", () => {
					listReads += 1;
					return HttpResponse.json({
						content: profileReviewRuns,
						hasNext: false,
						page: 0,
						size: 10,
					});
				}),
				http.get("*/workspaces/:workspaceSlug/practice-profile/review-runs/:reviewId", () =>
					HttpResponse.json({
						run: { ...openProfileReviewRun, status },
						observations: [observation],
					}),
				),
				http.get(
					"*/workspaces/:workspaceSlug/practices/trace/:artifactKind/:artifactId",
					async ({ request }) => {
						tracedReviewIds.push(new URL(request.url).searchParams.get("reviewId"));
						activityReads += 1;
						await held.shift();
						return HttpResponse.json(artifactTrace);
					},
				),
			);
			renderRouteAtWithRouter(
				`${PAGE}?detail=${encodeURIComponent(
					JSON.stringify(["reviews:all", `review:${openProfileReviewRun.reviewId}`]),
				)}`,
			);
			await vi.waitFor(() => {
				screen.getByText("Running");
				expect(activityReads).toBe(1);
				expect(listReads).toBe(1);
			}, ROUTE_RENDER_WAIT);

			// The activity answers half a poll late, which re-arms its poll there: the review's next poll
			// then falls while the activity's does not, so no poll of the activity can pass for the
			// read the review stopping asks for.
			await vi.advanceTimersByTimeAsync(ACTIVE_REVIEW_POLL_MS / 2);
			firstActivity.resolve();
			await vi.waitFor(
				() => screen.getByText("The review ended before it reached this practice."),
				ROUTE_RENDER_WAIT,
			);

			status = "COMPLETED";
			await vi.advanceTimersByTimeAsync(ACTIVE_REVIEW_POLL_MS / 2);
			await vi.waitFor(() => {
				expect(screen.queryByText("Running")).toBeNull();
				expect(activityReads).toBe(2);
				expect(listReads).toBe(2);
			}, SETTLE_WAIT);

			// A finished review's activity stops polling.
			await vi.advanceTimersByTimeAsync(3 * ACTIVE_REVIEW_POLL_MS);
			expect(activityReads).toBe(2);
			expect(new Set(tracedReviewIds)).toStrictEqual(new Set([openProfileReviewRun.reviewId]));
		} finally {
			vi.useRealTimers();
		}
	});

	it("shows a review that is not the reader's as not found, without asking again", async () => {
		let reads = 0;
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-profile/review-runs/:reviewId", () => {
				reads += 1;
				return HttpResponse.json({ status: 404, title: "Not Found" }, { status: 404 });
			}),
		);
		renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify(["review:gone"]))}`,
		);

		await screen.findByText("We could not find this review", undefined, ROUTE_RENDER_WAIT);
		await screen.findByText(/may have been deleted or moved/u);
		expect(reads).toBe(1);
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

	it("offers an administrator viewing as the developer no review to start", async () => {
		storeUserView({ workspaceSlug: "acme", login: "ada", name: "Ada" });
		await renderProfile();
		fireEvent.click(await openReviewsChip());

		await screen.findByRole("button", { name: openReviewRowName }, ROUTE_RENDER_WAIT);
		expect(screen.queryAllByRole("button", { name: /^Review .* now$/u })).toHaveLength(0);
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
