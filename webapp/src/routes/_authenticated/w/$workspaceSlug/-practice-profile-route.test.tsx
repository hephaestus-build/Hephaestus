import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import type {
	PracticeProfileOverview,
	PracticeGuidanceDto,
	PracticeGroupStanding,
	PracticePageFeedback,
	ProfileReview,
	ProfileReviewDetail,
	ResultDetail,
	ReviewedPractice,
	WorkspaceMembership,
} from "@/api/types.gen";
import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { reviewRangeStart } from "@/components/practice-profile/practice-profile-search";
import { formatDayTime } from "@/lib/dates";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { clearUserView } from "@/runtime/user-view/session";
import { detailResult, detailReview } from "@/stories/practice-detail-story-mock-data";
import { bundledGuidance } from "@/stories/practice-guidance-story-mock-data";
import {
	groups,
	groupStandings,
	OVERVIEW_FIXTURE,
	packagingGroup,
	packagingStanding,
	practiceStandings,
	pullRequest,
	READY_REVIEW_READINESS,
} from "@/stories/practice-profile-story-mock-data";
import {
	openProfileReview,
	openProfileReviewDetail,
	profileReviews,
} from "@/stories/profile-reviews-story-mock-data";
import { STORY_NOW } from "@/stories/story-clock";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";
import { storeUserView } from "@/test/user-view";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: ROUTE_RENDER_WAIT.timeout });

const PAGE = "/w/acme/practice-profile";

const stack = (router: { state: { location: { search: { detail?: unknown } } } }) =>
	stackInSearch(router.state.location.search.detail);

/** Long enough for a response to render once the route is up, short of the test's own timeout. */
const SETTLE_WAIT = { timeout: 5000 } as const;

const [first] = practiceStandings;
if (!first) {
	throw new Error("The fixtures carry at least one practice with a standing");
}
/** Narrowed once, so the helpers below can read it without a guard each. */
const practice = first;

const [ownResult] = detailReview.results;
if (!ownResult) {
	throw new Error("The detail review carries a result");
}

const ownSummary = ownResult.summary;
if (ownSummary === null) {
	throw new Error("The detail review's result has a summary");
}

/** The review before the open one, on the same merge request. */
const [, earlier] = profileReviews;
if (!earlier) {
	throw new Error("The fixtures carry a second review");
}

const [reused, ...unreused] = openProfileReviewDetail.results;
if (!reused) {
	throw new Error("The open review carries a result");
}
const reusingDetail: ProfileReviewDetail = {
	...openProfileReviewDetail,
	results: [{ ...reused, reviewId: earlier.review.id }, ...unreused],
};

const groupReview: ProfileReview = {
	...detailReview,
	results: [{ ...ownResult, practiceSlug: practice.slug, practiceName: practice.name }],
};

const observationNextSteps = detailResult.problems
	.map((problem) => problem.nextStep)
	.filter((step) => step !== null);

const membership: WorkspaceMembership = {
	account: {
		id: "1",
		displayName: "Ada",
		avatarUrl: null,
		status: "ACTIVE",
		identities: [{ provider: "GITHUB", login: "ada", displayName: "Ada", avatarUrl: null }],
	},
	role: "MEMBER",
	eligibleForPracticeReview: true,
	hidden: false,
	reviewSelected: true,
	joinedAt: new Date("2026-01-01T00:00:00Z"),
	leftAt: null,
};

const reviewedPractices: ReviewedPractice[] = practiceStandings.map((standing) => ({
	slug: standing.slug,
	name: standing.name,
	group: groups.find((candidate) => candidate.slug === standing.groupSlug) ?? null,
	whyItMatters: null,
	whatGoodLooksLike: null,
}));

const plainGuidance: PracticeGuidanceDto = {
	practiceSlug: "scope-the-change",
	visual: null,
	guide: null,
};

const pageOf = (reviews: ProfileReview[]) => ({
	content: reviews,
	number: 0,
	size: 10,
	totalElements: reviews.length,
	totalPages: 1,
	last: true,
});

function reviewDetailOf(reviewId: string): ProfileReviewDetail | undefined {
	if (reviewId === openProfileReview.review.id) {
		return openProfileReviewDetail;
	}
	const listed = profileReviews.find((entry) => entry.review.id === reviewId);
	return (
		listed && {
			...listed,
			practiceOutcomes: { met: 0, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 },
			trace: {
				item: listed.review.item,
				review: listed.review,
				events: [],
				results: listed.results,
			},
		}
	);
}

const notFound = () => HttpResponse.json({ status: 404, title: "Not Found" }, { status: 404 });

function reviewReads(open: ProfileReviewDetail = openProfileReviewDetail) {
	return http.get(
		"*/workspaces/:workspaceSlug/practice-profile/reviews/:reviewId",
		({ params }) => {
			const found =
				params.reviewId === open.review.id ? open : reviewDetailOf(String(params.reviewId));
			return found ? HttpResponse.json(found) : notFound();
		},
	);
}

beforeEach(() => {
	vi.stubGlobal("IntersectionObserver", ObserverStub);
	server.use(
		// The surface exists only where practices review the work, and the shared fixture has them
		// off, so every case below has to say that this workspace reviews.
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () => HttpResponse.json(membership)),
		http.get("*/workspaces/:workspaceSlug/practices/reviewed", () =>
			HttpResponse.json(reviewedPractices),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () =>
			HttpResponse.json(Object.values(groupStandings)),
		),
		http.get("*/workspaces/:workspaceSlug/practices/standings", () =>
			HttpResponse.json(practiceStandings),
		),
		http.get("*/workspaces/:workspaceSlug/practice-profile/overview", () =>
			HttpResponse.json(OVERVIEW_FIXTURE),
		),
		http.get("*/workspaces/:workspaceSlug/practices/feedback/practice-page", () =>
			HttpResponse.json([]),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/reviews", () =>
			HttpResponse.json(pageOf([groupReview])),
		),
		http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/guidance", () =>
			HttpResponse.json(plainGuidance),
		),
		http.get("*/workspaces/:workspaceSlug/practice-profile/reviews", () =>
			HttpResponse.json(pageOf(profileReviews)),
		),
		reviewReads(),
		http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
			HttpResponse.json(detailResult),
		),
	);
});

/** The open review, by its address, over the list of reviews. */
const OPEN_REVIEW_PAGE = `${PAGE}?detail=${encodeURIComponent(
	JSON.stringify(["reviews:all", `review:${openProfileReview.review.id}`]),
)}`;

const openReviewRowName = `See the reviewed practices for ${openProfileReview.review.item.label}, ${formatDayTime(
	openProfileReview.review.completedAt ?? openProfileReview.review.createdAt,
	new Date(STORY_NOW),
)}`;

async function renderProfile(path = PAGE) {
	const { router } = renderRouteAtWithRouter(path);
	await screen.findByRole("heading", { level: 1, name: "Practice profile" }, ROUTE_RENDER_WAIT);
	return router;
}

const openReviewsChip = async () =>
	screen.findByRole("link", { name: /^Latest review/u }, ROUTE_RENDER_WAIT);

const group = `practice-group:${packagingGroup.slug}`;
const practiceEntry = `practice:${practice.slug}`;

const emptyGroup = { ...packagingGroup, slug: "empty-group", name: "Nothing in here" };

/** A shown group with nothing reviewed in it: the standings name every shown group. */
function useEmptyGroup() {
	server.use(
		http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () =>
			HttpResponse.json([
				...Object.values(groupStandings),
				{
					...packagingStanding,
					groupSlug: emptyGroup.slug,
					groupName: emptyGroup.name,
					standing: "NOT_OBSERVED",
					direction: null,
					trendSupport: null,
					results: [],
					sources: [],
					feedbackSince: null,
					feedbackSpanDays: null,
				} satisfies PracticeGroupStanding,
			]),
		),
	);
}

const hiddenGroup = { ...packagingGroup, slug: "hidden-group", name: "Hidden from developers" };

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
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			"practice-groups:all",
			group,
			practiceEntry,
		]),
	);
}

async function showResults() {
	fireEvent.click(
		await screen.findByRole("tab", { name: /^Observations/u, selected: false }, ROUTE_RENDER_WAIT),
	);
	await screen.findByRole("tab", { name: /^Observations/u, selected: true });
}

/**
 * What the route owns and no story can see: how the page's controls are spelled in the URL. The
 * drawer's levels are addressed by the `detail` stack, and the page's tab and the table's sort are
 * silent at their defaults so the address a reader shares is the shortest one that means the same.
 */
describe("practice profile route", () => {
	it("sends a reader away when this workspace does not review practices, without delivering feedback", async () => {
		const practicePage = vi.fn(() => HttpResponse.json([]));
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { practicesEnabled: false })]),
			),
			http.get("*/workspaces/:workspaceSlug/practices/feedback/practice-page", practicePage),
		);
		const { router } = renderRouteAtWithRouter(PAGE);

		// With practices off the page does not exist here, so the reader lands on the workspace home,
		// which is Activity for such a workspace, rather than on a profile with nothing to be about.
		await waitFor(() => {
			expect(router.state.location.pathname).toBe("/w/acme/activity");
			expect(router.state.status).toBe("idle");
		}, ROUTE_RENDER_WAIT);
		// Reading the cards is what delivers them: a reader who is sent away must never have their
		// unread feedback marked delivered on the way out.
		expect(practicePage).not.toHaveBeenCalled();
	});

	it("stacks the list, a group and a practice in the detail param, one history entry each", async () => {
		const router = await renderProfile();
		const entries = router.history.length;

		const detail = () => stackInSearch(router.state.location.search.detail);

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

	it("lists no group without a practice", async () => {
		useEmptyGroup();
		// The drawer over the page hides the page's own heading, so the level is waited for itself.
		renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify(["practice-groups:all"]))}`,
		);

		const table = await screen.findByRole(
			"table",
			{ name: "All practice groups" },
			ROUTE_RENDER_WAIT,
		);
		await within(table).findByRole(
			"button",
			{ name: `Open group ${packagingGroup.name}` },
			ROUTE_RENDER_WAIT,
		);
		expect(
			within(table).queryByRole("button", { name: `Open group ${emptyGroup.name}` }),
		).toBeNull();
	});

	it("opens a link to a group without a practice on a level that says so", async () => {
		useEmptyGroup();
		renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify([`practice-group:${emptyGroup.slug}`]))}`,
		);

		await screen.findByText("No reviewed practices", undefined, ROUTE_RENDER_WAIT);
		screen.getByRole("heading", { name: emptyGroup.name });
		expect(screen.queryByText("We could not find this practice group")).toBeNull();
	});

	it("leaves out a hidden group even when a reviewed practice names it", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/reviewed", () =>
				HttpResponse.json([
					...reviewedPractices,
					{
						slug: "secret-practice",
						name: "A practice in a hidden group",
						group: hiddenGroup,
						whyItMatters: null,
						whatGoodLooksLike: null,
					} satisfies ReviewedPractice,
				]),
			),
		);
		renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify(["practice-groups:all"]))}`,
		);

		const table = await screen.findByRole(
			"table",
			{ name: "All practice groups" },
			ROUTE_RENDER_WAIT,
		);
		await within(table).findByRole(
			"button",
			{ name: `Open group ${packagingGroup.name}` },
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByText(hiddenGroup.name)).toBeNull();
	});

	it("shows a group's own description on its level", async () => {
		const description = "Work a reviewer can start on without asking first.";
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () =>
				HttpResponse.json([{ ...packagingStanding, groupDescription: description }]),
			),
		);
		renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify([`practice-group:${packagingGroup.slug}`]))}`,
		);

		await screen.findByText(description, undefined, ROUTE_RENDER_WAIT);
		screen.getByRole("heading", { name: packagingGroup.name });
	});

	it("never asks the admin-only list of practice groups", async () => {
		const adminGroups = vi.fn(() => HttpResponse.json([]));
		server.use(http.get("*/workspaces/:workspaceSlug/practice-groups", adminGroups));
		await renderProfile();
		await screen.findByRole("link", { name: "See all practice groups" }, ROUTE_RENDER_WAIT);

		expect(adminGroups).not.toHaveBeenCalled();
	});

	it("opens every review of the reader's work from the chip, then one review over it", async () => {
		const router = await renderProfile();
		const detail = () => stackInSearch(router.state.location.search.detail);

		fireEvent.click(await openReviewsChip());
		await waitFor(() => expect(detail()).toStrictEqual(["reviews:all"]));
		await screen.findByRole("heading", { name: "Reviews of your work" }, ROUTE_RENDER_WAIT);

		fireEvent.click(
			await screen.findByRole("button", { name: openReviewRowName }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() =>
			expect(detail()).toStrictEqual(["reviews:all", `review:${openProfileReview.review.id}`]),
		);
		const table = await waitFor(() => {
			const loaded = screen.getByRole("table", { name: "Every practice in this review" });
			expect(loaded.getAttribute("aria-busy")).not.toBe("true");
			return loaded;
		}, ROUTE_RENDER_WAIT);
		await within(table).findByRole("button", { name: "Thin controllers" }, SETTLE_WAIT);
		// The row under the level and the head over it.
		await waitFor(() => expect(screen.getAllByText("Review 2 of 2")).toHaveLength(2));

		// One level per history entry, so Back closes exactly one and then the drawer.
		router.history.back();
		await waitFor(() => expect(detail()).toStrictEqual(["reviews:all"]));
		router.history.back();
		await waitFor(() => expect(detail()).toStrictEqual([]));
	});

	it("asks the reviews list for a tracked work and a status, and lets go of both on Reset", async () => {
		const asked: { itemId: string | null; status: string | null }[] = [];
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-profile/reviews", ({ request }) => {
				const params = new URL(request.url).searchParams;
				asked.push({ itemId: params.get("itemId"), status: params.get("status") });
				return HttpResponse.json(pageOf(profileReviews));
			}),
		);
		const tracked = openProfileReview.review.item.id;
		const { router } = renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify(["reviews:all"]))}&reviewItem=${tracked}&reviewStatus=PARTIAL`,
		);
		await screen.findByRole("heading", { name: "Reviews of your work" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(asked.at(-1)).toStrictEqual({ itemId: tracked, status: "PARTIAL" }));
		fireEvent.click(screen.getByRole("button", { name: /^Reset/u }));
		await waitFor(() => expect(router.state.location.search.reviewItem).toBeUndefined());
		expect(router.state.location.search.reviewStatus).toBeUndefined();
		await waitFor(() => expect(asked.at(-1)).toStrictEqual({ itemId: null, status: null }));
	});

	it("turns the chosen timeframe into the since the reviews list asks for", async () => {
		// Fixed at noon, so the day the bound is counted from cannot turn during the test.
		const noon = new Date(2026, 8, 22, 12, 0);
		vi.useFakeTimers({ toFake: ["Date"] });
		vi.setSystemTime(noon);
		try {
			const asked: (string | null)[] = [];
			server.use(
				http.get("*/workspaces/:workspaceSlug/practice-profile/reviews", ({ request }) => {
					asked.push(new URL(request.url).searchParams.get("since"));
					return HttpResponse.json(pageOf(profileReviews));
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
				expect(asked.at(-1)).toBe(reviewRangeStart(noon.getTime(), "30d").toISOString()),
			);
		} finally {
			vi.useRealTimers();
		}
	});

	it("says why a review asked for from a row was refused", async () => {
		const asked: unknown[] = [];
		server.use(
			http.post("*/workspaces/:workspaceSlug/practices/review-requests", async ({ request }) => {
				asked.push(await request.json());
				return HttpResponse.json({
					status: "REFUSED",
					reason: "REQUEST_COOLDOWN_ACTIVE",
					reviewId: null,
				});
			}),
		);
		await renderProfile();
		fireEvent.click(await openReviewsChip());

		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Request another review: #890" },
				ROUTE_RENDER_WAIT,
			),
		);

		await screen.findByText(
			"A review was requested recently, so another request waits.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		const failed = profileReviews.find((entry) => entry.review.item.label === "#890");
		expect(asked).toStrictEqual([{ itemId: failed?.review.item.id }]);
	});

	it("opens the earlier review that recorded a reused result in place of this one, without this one's filter", async () => {
		server.use(reviewReads(reusingDetail));
		const { router } = renderRouteAtWithRouter(
			`${OPEN_REVIEW_PAGE}&reviewPractice=thin&feedback=resolved`,
		);

		fireEvent.click(
			await screen.findByRole("link", { name: "Open the earlier review" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() =>
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
				"reviews:all",
				`review:${earlier.review.id}`,
			]),
		);
		// The filter belonged to the review it replaced; the page's own tab is the reader's still.
		expect(router.state.location.search.reviewPractice).toBeUndefined();
		expect(router.state.location.search.feedback).toBe("resolved");
		const table = await waitFor(() => {
			const loaded = screen.getByRole("table", { name: "Every practice in this review" });
			expect(loaded.getAttribute("aria-busy")).not.toBe("true");
			return loaded;
		}, ROUTE_RENDER_WAIT);
		await within(table).findByRole("button", { name: "Explain each change" }, SETTLE_WAIT);
	});

	it("answers an old What we noticed link with every practice, and lists no recorded activity", async () => {
		server.use(
			reviewReads({
				...openProfileReviewDetail,
				trace: {
					...openProfileReviewDetail.trace,
					events: [
						{
							name: "scm.pull_request.ready",
							displayName: "Marked ready for review",
							occurredAt: new Date("2026-10-19T12:00:00Z"),
						},
					],
				},
			}),
		);
		renderRouteAtWithRouter(`${OPEN_REVIEW_PAGE}&reviewTab=noticed`);
		await screen.findByRole("table", { name: "Every practice in this review" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("tab", { name: /What we noticed/u })).toBeNull();
		expect(screen.queryByText("Marked ready for review")).toBeNull();
	});

	it("shows a review that is not the reader's as not found, without asking again", async () => {
		let reads = 0;
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-profile/reviews/:reviewId", () => {
				reads += 1;
				return notFound();
			}),
		);
		renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(
				JSON.stringify(["review:00000000-0000-0000-0000-00000000dead"]),
			)}`,
		);

		await screen.findByText("We could not find this review", undefined, ROUTE_RENDER_WAIT);
		expect(reads).toBe(1);
	});

	it("shows the practice's own results from its group's reviews, and no other practice's", async () => {
		const router = await renderProfile();
		await openPractice(router);
		await showResults();
		await screen.findAllByText(ownSummary, undefined, SETTLE_WAIT);
		await screen.findByRole("tab", { name: "Observations 1" }, SETTLE_WAIT);
	});

	it("loads the complete observation when its practice row opens", async () => {
		const reads: string[] = [];
		const explanation =
			"The practice was checked against the complete change, not only its problem summaries.";
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", ({ params }) => {
				reads.push(String(params.resultId));
				return HttpResponse.json({
					...detailResult,
					id: ownResult.id,
					practiceSlug: practice.slug,
					practiceName: practice.name,
					explanation,
				} satisfies ResultDetail);
			}),
		);
		const router = await renderProfile();
		await openPractice(router);
		expect(reads).toStrictEqual([]);
		await showResults();
		await screen.findByText(explanation, undefined, SETTLE_WAIT);
		expect(reads).toStrictEqual([ownResult.id]);
		for (const nextStep of observationNextSteps) {
			await screen.findByText(nextStep);
		}
	});

	it("clears the selection with the level when the level is closed forward", async () => {
		// Arrived by address: nothing behind the level to go back to, so closing writes a new one.
		// The page under the open drawer is hidden from the accessibility tree, so the level's own
		// heading is what says the route has rendered.
		const { router } = renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify([group, practiceEntry]))}&practiceTab=results`,
		);
		await screen.findByRole("heading", { name: practice.name }, ROUTE_RENDER_WAIT);
		const search = () => router.state.location.search;
		expect(search().practiceTab).toBe("results");

		fireEvent.click(screen.getByRole("button", { name: "Back" }));

		await waitFor(() => expect(stackInSearch(search().detail)).toStrictEqual([group]));
		expect(router.state.location.searchStr).not.toContain("practiceTab");
	});

	it("opens a practice tab it does not know on the feedback, and drops it from the URL", async () => {
		const { router } = renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify([group, practiceEntry]))}&practiceTab=about`,
		);
		await screen.findByRole("heading", { name: practice.name }, ROUTE_RENDER_WAIT);

		const feedback = await screen.findByRole("tab", { name: /^Feedback/u }, SETTLE_WAIT);
		expect(feedback.getAttribute("aria-selected")).toBe("true");
		await waitFor(() => expect(router.state.location.searchStr).not.toContain("practiceTab"));
	});

	it("opens a link to the guide on the introduction and picture when the practice has no long guide", async () => {
		const { visual } = bundledGuidance;
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/guidance", () =>
				HttpResponse.json({ ...plainGuidance, visual }),
			),
		);
		renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify([group, practiceEntry]))}&practiceTab=guide`,
		);
		// The picture is in, so the guidance has settled without a guide.
		const picture = await screen.findByRole("img", { name: visual.alt }, ROUTE_RENDER_WAIT);

		const tab = await screen.findByRole("tab", { name: "Guide" }, SETTLE_WAIT);
		expect(tab.getAttribute("aria-selected")).toBe("true");
		const panel = screen.getByRole("tabpanel", { name: "Guide" });
		expect(within(panel).getByRole("region", { name: "Introduction" }).contains(picture)).toBe(
			true,
		);
		expect(within(panel).queryByRole("heading", { name: "Practice guide" })).toBeNull();
	});

	it("keeps the URL silent on the default feedback tab and spells every other one", async () => {
		const router = await renderProfile();

		fireEvent.click(await screen.findByRole("tab", { name: /^Resolved/u }));
		await waitFor(() => expect(router.state.location.search.feedback).toBe("resolved"));
		// The tab is selected once the page has read it back from the URL; a press on a tab that is
		// still selected in the DOM is not a change.
		fireEvent.click(await screen.findByRole("tab", { name: /^Open/u, selected: false }));

		await waitFor(() => expect(router.state.location.searchStr).toBe(""));
	});

	it("links to itself without the default view, as the sidebar does", async () => {
		const router = await renderProfile();
		await screen.findByRole("link", { name: "See all practice groups" }, ROUTE_RENDER_WAIT);

		const location = router.buildLocation({
			to: "/w/$workspaceSlug/practice-profile",
			params: { workspaceSlug: "acme" },
		});
		expect(location.searchStr).toBe("");
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
		await waitFor(() =>
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([]),
		);
	});
});

/** A Met result of the developer's own, on a pull request the overview lists. */
const MET = {
	resultId: "0b9c6f0e-0000-4000-8000-000000000022",
	reviewId: "5d1e2a3b-0000-4000-8000-000000000022",
	item: pullRequest(22),
	practiceSlug: "ready-and-traceable-handoff",
	practiceName: "Mark the change ready and link its issue",
	summary: "The pull request is marked ready and closes its issue",
};

describe("a met result", () => {
	const metReview: ProfileReviewDetail = {
		...openProfileReviewDetail,
		review: { ...openProfileReviewDetail.review, id: MET.reviewId, item: MET.item },
		results: [
			...openProfileReviewDetail.results,
			{
				...ownResult,
				id: MET.resultId,
				reviewId: MET.reviewId,
				item: MET.item,
				practiceSlug: MET.practiceSlug,
				practiceName: MET.practiceName,
				outcome: "MET",
				severity: null,
				summary: MET.summary,
				problems: [],
			},
		],
	};

	it("gets no section of its own: the feedback follows the header", async () => {
		await renderProfile();
		await screen.findByRole("heading", { level: 2, name: "Your feedback" }, ROUTE_RENDER_WAIT);
		// The header still counts the practices going well; only the section is gone.
		expect(practiceStandings.some((entry) => entry.standing === "STRENGTH")).toBe(true);
		expect(screen.queryByRole("region", { name: "Going well" })).toBeNull();
	});

	it("opens in its own review, at that result, from a link to it", async () => {
		const reviewsRead: string[] = [];
		const resultsRead: string[] = [];
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-profile/reviews/:reviewId", ({ params }) => {
				reviewsRead.push(String(params.reviewId));
				return HttpResponse.json(metReview);
			}),
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", ({ params }) => {
				resultsRead.push(String(params.resultId));
				return HttpResponse.json({
					...detailResult,
					id: MET.resultId,
					reviewId: MET.reviewId,
					item: MET.item,
					practiceSlug: MET.practiceSlug,
					practiceName: MET.practiceName,
					outcome: "MET",
					severity: null,
					summary: MET.summary,
					problems: [],
					explanation:
						"The description opens with Closes #41 and the pull request left draft first.",
				} satisfies ResultDetail);
			}),
		);
		const detail = encodeURIComponent(JSON.stringify([`review:${MET.reviewId}`]));
		const { router } = renderRouteAtWithRouter(`${PAGE}?detail=${detail}`);
		await waitFor(() => expect(reviewsRead).toContain(MET.reviewId), SETTLE_WAIT);
		await screen.findByRole("table", { name: "Every practice in this review" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("link", { name: `Open observation for ${MET.practiceName}` }));
		await waitFor(
			() => expect(router.state.location.search.observation).toBe(MET.resultId),
			SETTLE_WAIT,
		);
		await waitFor(() => expect(resultsRead).toStrictEqual([MET.resultId]), SETTLE_WAIT);
		await screen.findByRole("list", { name: "Reviews" }, SETTLE_WAIT);
		// The link filters the practice's observations to its work instead of pinning a second copy.
		expect(router.state.location.search.observationQuery).toBe(MET.item.label);
		// It narrows to that one work by id, so "#15" never also keeps "#150".
		expect(router.state.location.search.observationWork).toBe(MET.item.id);
		const search = await screen.findByRole(
			"searchbox",
			{ name: "Search observations" },
			SETTLE_WAIT,
		);
		assert(search instanceof HTMLInputElement);
		expect(search.value).toBe(MET.item.label);
		await screen.findByText(
			"The description opens with Closes #41 and the pull request left draft first.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			`review:${MET.reviewId}`,
			`practice:${MET.practiceSlug}`,
		]);
		expect(router.state.location.search.observation).toBe(MET.resultId);
		expect(reviewsRead).toContain(MET.reviewId);
		expect(resultsRead).toStrictEqual([MET.resultId]);
	});
});

// The self endpoint owns evidence visibility; this route may show only the fields it returns.
describe("own result", () => {
	it("shows a result whose evidence was withheld without inventing proof", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
				HttpResponse.json({
					...detailResult,
					id: reused.id,
					// The detail of the same observation: its work is the one the link filters to.
					item: reused.item,
					practiceSlug: reused.practiceSlug,
					explanation: null,
					evidence: null,
					example: null,
					coverage: null,
					problems: detailResult.problems.map((problem) => ({
						...problem,
						explanation: null,
						citations: [],
					})),
				} satisfies ResultDetail),
			),
		);
		const { router } = renderRouteAtWithRouter(OPEN_REVIEW_PAGE);
		fireEvent.click(
			await screen.findByRole(
				"link",
				{ name: `Open observation for ${reused.practiceName}` },
				ROUTE_RENDER_WAIT,
			),
		);

		await screen.findByText(
			"The caching change hides inside the package move",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.search.observation).toBe(reused.id);
		expect(screen.queryByText(/PracticeCatalogLoader/u)).toBeNull();
		expect(screen.queryByText(/so a reviewer cannot tell which hunk/u)).toBeNull();
	});
});

const completeObservation: ResultDetail = {
	...detailResult,
	id: ownResult.id,
	practiceSlug: practice.slug,
	practiceName: practice.name,
};
const observationPage = (id = completeObservation.id, slug = practice.slug) =>
	`${PAGE}?detail=${encodeURIComponent(JSON.stringify([`practice:${slug}`]))}&practiceTab=results&observation=${id}`;

describe("complete practice observations", () => {
	afterEach(clearUserView);
	it("shows every problem, available next step, evidence and recorded feedback link together", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
				HttpResponse.json(completeObservation),
			),
		);
		const { router } = renderRouteAtWithRouter(observationPage());
		await waitFor(
			() => expect(router.state.location.search.observation).toBe(completeObservation.id),
			SETTLE_WAIT,
		);
		const selected = await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		for (const problem of completeObservation.problems) {
			await within(selected).findByText(problem.summary);
		}
		expect(completeObservation.problems).toHaveLength(3);
		expect(observationNextSteps).toHaveLength(2);
		for (const nextStep of observationNextSteps) {
			await within(selected).findByText(nextStep);
		}
		expect(selected.textContent).toContain(
			'@Cacheable(cacheNames = "practice-catalog", key = "#workspaceId")',
		);
		expect(within(selected).getAllByRole("link", { name: /^Read comment \d/u })).toHaveLength(2);
		// The observation lists the feedback it gave and where; it is answered where it is, not here.
		within(selected).getByRole("list", { name: "Feedback from this observation" });
		expect(
			within(selected).queryByRole("group", { name: /Your response to the feedback/u }),
		).toBeNull();
	});

	it("does not read a closed older row until it is expanded", async () => {
		const olderId = "00000000-0000-0000-0000-00000000abcd";
		const reads: string[] = [];
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/reviews", () =>
				HttpResponse.json(
					pageOf([
						groupReview,
						{
							...groupReview,
							review: earlier.review,
							feedback: [],
							results: [
								{
									...ownResult,
									practiceSlug: practice.slug,
									practiceName: practice.name,
									id: olderId,
									reviewId: earlier.review.id,
									item: earlier.review.item,
									summary: "The older observation waits for expansion",
								},
							],
						},
					]),
				),
			),
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", ({ params }) => {
				const id = String(params.resultId);
				reads.push(id);
				return HttpResponse.json({ ...completeObservation, id });
			}),
		);
		const router = await renderProfile();
		await openPractice(router);
		await showResults();
		assert(completeObservation.explanation !== null);
		await screen.findByText(completeObservation.explanation, undefined, ROUTE_RENDER_WAIT);
		expect(reads).toStrictEqual([ownResult.id]);
		fireEvent.click(
			screen.getByRole("button", { name: /^The older observation waits for expansion/u }),
		);
		await waitFor(() => expect(reads).toStrictEqual([ownResult.id, olderId]));
	});

	it("keeps the selected observation read-only when an administrator views as its developer", async () => {
		storeUserView({ workspaceSlug: "acme" });
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
				HttpResponse.json(completeObservation),
			),
		);
		renderRouteAtWithRouter(observationPage());
		const selected = await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		expect(within(selected).queryByRole("group", { name: /Your response/u })).toBeNull();
		expect(within(selected).queryByRole("button", { name: "I fixed this" })).toBeNull();
		expect(within(selected).getAllByRole("link", { name: /^Read comment \d/u })).toHaveLength(2);
		clearUserView();
	});

	it("opens an exact older observation without fetching missing feed pages, then keeps one copy when history arrives", async () => {
		const reads: string[] = [];
		const pages: (string | null)[] = [];
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", ({ params }) => {
				reads.push(String(params.resultId));
				return HttpResponse.json(completeObservation);
			}),
			// The newest page holds no review of this practice; the next one holds the selected observation.
			http.get(
				"*/workspaces/:workspaceSlug/practice-groups/:groupSlug/reviews",
				({ request }) => {
					pages.push(new URL(request.url).searchParams.get("page"));
					return HttpResponse.json({ ...pageOf([]), totalPages: 2, totalElements: 1, last: false });
				},
				{ once: true },
			),
			http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/reviews", ({ request }) => {
				pages.push(new URL(request.url).searchParams.get("page"));
				return HttpResponse.json({ ...pageOf([groupReview]), number: 1, totalPages: 2 });
			}),
		);
		renderRouteAtWithRouter(observationPage());
		const selected = await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		assert(completeObservation.explanation !== null);
		await within(selected).findByText(completeObservation.explanation);
		expect(reads).toStrictEqual([completeObservation.id]);
		expect(pages).toStrictEqual(["0"]);
		fireEvent.click(screen.getByRole("button", { name: "View earlier reviews" }));
		await waitFor(() => expect(pages).toStrictEqual(["0", "1"]));
		await waitFor(() =>
			expect(screen.queryByRole("button", { name: "View earlier reviews" })).toBeNull(),
		);
		expect(screen.getAllByText(ownSummary)).toHaveLength(1);
		expect(pages).toStrictEqual(["0", "1"]);
		expect(reads).toStrictEqual([completeObservation.id]);
	});

	it("uses the exact reused observation link and returns to the same review with browser Back", async () => {
		const reads: string[] = [];
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", ({ params }) => {
				reads.push(String(params.resultId));
				return HttpResponse.json({
					...completeObservation,
					id: reused.id,
					item: reused.item,
					practiceSlug: reused.practiceSlug,
					practiceName: reused.practiceName,
				});
			}),
		);
		const { router } = renderRouteAtWithRouter(OPEN_REVIEW_PAGE);
		const link = await screen.findByRole(
			"link",
			{ name: `Open observation for ${reused.practiceName}` },
			ROUTE_RENDER_WAIT,
		);
		expect(reads).toStrictEqual([]);
		expect(screen.queryByRole("button", { name: /^Show evidence/u })).toBeNull();
		expect(link.getAttribute("href")).toContain(reused.id);
		fireEvent.click(link);
		await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.search.observation).toBe(reused.id);
		expect(reads).toStrictEqual([reused.id]);
		router.history.back();
		await screen.findByRole("table", { name: "Every practice in this review" }, ROUTE_RENDER_WAIT);
		expect(stack(router)).toStrictEqual(["reviews:all", `review:${openProfileReview.review.id}`]);
		expect(router.state.location.search.observation).toBeUndefined();
	});

	it("keeps the filter in the address and lets go of the linked observation once the reader types", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
				HttpResponse.json(completeObservation),
			),
		);
		const { router } = renderRouteAtWithRouter(observationPage());
		const search = await screen.findByRole(
			"searchbox",
			{ name: "Search observations" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.change(search, { target: { value: "#1" } });
		await waitFor(() => expect(router.state.location.search.observationQuery).toBe("#1"));
		expect(router.state.location.search.observation).toBeUndefined();
		expect(router.state.location.search.observationWork).toBeUndefined();
		fireEvent.click(screen.getByRole("button", { name: "Reset" }));
		await waitFor(() => expect(router.state.location.search.observationQuery).toBeUndefined());
	});

	it("reads a selected observation for a practice with no standing or group", async () => {
		const slug = "retained-practice";
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
				HttpResponse.json({ ...completeObservation, practiceSlug: slug }),
			),
		);
		renderRouteAtWithRouter(observationPage(completeObservation.id, slug));
		assert(completeObservation.explanation !== null);
		await screen.findByText(completeObservation.explanation, undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("No observations yet")).toBeNull();
	});

	it("rejects a malformed observation UUID before making a detail request", async () => {
		let reads = 0;
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () => {
				reads += 1;
				return HttpResponse.json(completeObservation);
			}),
		);
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/reviews", () =>
				HttpResponse.json(pageOf([])),
			),
		);
		const { router } = renderRouteAtWithRouter(observationPage("not-a-uuid"));
		await screen.findByRole("heading", { name: practice.name }, ROUTE_RENDER_WAIT);
		// The feed can legitimately read its newest observation; give it no row so the malformed selection is isolated.
		expect(router.state.location.search.observation).toBeUndefined();
		expect(reads).toBe(0);
	});

	it.each([
		{ id: "00000000-0000-0000-0000-00000000ffff", practiceSlug: practice.slug },
		{ id: completeObservation.id, practiceSlug: "another-practice" },
	])(
		"refuses detail whose identity differs from the selected practice or UUID: $id/$practiceSlug",
		async (identity) => {
			server.use(
				http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
					HttpResponse.json({
						...completeObservation,
						...identity,
						explanation: "UNRELATED_OBSERVATION_CANARY",
					}),
				),
			);
			renderRouteAtWithRouter(observationPage());
			await screen.findByText(
				"This observation does not belong to this practice. Open it from its review.",
				undefined,
				ROUTE_RENDER_WAIT,
			);
			expect(screen.queryByText("UNRELATED_OBSERVATION_CANARY")).toBeNull();
		},
	);

	it("shows no detail or retry when the own endpoint refuses access", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
				HttpResponse.json({ status: 403, title: "Forbidden" }, { status: 403 }),
			),
		);
		renderRouteAtWithRouter(observationPage());
		await screen.findByText(
			"You do not have access to this observation",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
		expect(screen.queryByRole("list", { name: "Reviews" })).toBeNull();
	});

	it("restores the exact selected observation after reloading its address", async () => {
		let reads = 0;
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () => {
				reads += 1;
				return HttpResponse.json(completeObservation);
			}),
		);
		const { router } = renderRouteAtWithRouter(observationPage());
		await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		const address = router.state.location.href;
		cleanup();
		renderRouteAtWithRouter(address);
		await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		expect(reads).toBe(2);
	});

	it("leads with the selected observation and keeps it through a visit to the Guide tab", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/guidance", () =>
				HttpResponse.json(bundledGuidance),
			),
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
				HttpResponse.json(completeObservation),
			),
		);
		const { router } = renderRouteAtWithRouter(observationPage());
		const selected = await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		const observations = screen.getByRole("tabpanel", { name: /^Observations/u });
		expect(within(observations).getByRole("list", { name: "Reviews" })).toBe(selected);
		// The guidance carries a picture and a guide; neither stands above the observation.
		const guideTab = await screen.findByRole("tab", { name: "Guide" }, SETTLE_WAIT);
		expect(screen.queryByRole("region", { name: "Introduction" })).toBeNull();

		fireEvent.click(guideTab);
		const intro = await screen.findByRole("region", { name: "Introduction" }, SETTLE_WAIT);
		await within(intro).findByRole("img", { name: bundledGuidance.visual.alt }, SETTLE_WAIT);
		screen.getByRole("heading", { level: 4, name: "How to do it" });
		await waitFor(() => expect(screen.queryByRole("list", { name: "Reviews" })).toBeNull());
		await waitFor(() => expect(router.state.location.search.practiceTab).toBe("guide"));
		expect(router.state.location.search.observation).toBe(completeObservation.id);

		fireEvent.click(screen.getByRole("tab", { name: /^Observations/u }));
		const back = await screen.findByRole("list", { name: "Reviews" }, SETTLE_WAIT);
		assert(completeObservation.explanation !== null);
		await within(back).findByText(completeObservation.explanation);
		expect(router.state.location.search.observation).toBe(completeObservation.id);
		expect(screen.queryByRole("region", { name: "Introduction" })).toBeNull();
	});

	it("does not invent explanation or evidence when the retained detail makes them unavailable", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
				HttpResponse.json({
					...completeObservation,
					outcome: "COULD_NOT_DETERMINE",
					explanation: null,
					problems: [],
					evidence: null,
					example: null,
					feedback: [],
					severity: null,
				}),
			),
		);
		renderRouteAtWithRouter(observationPage());
		const selected = await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		await within(selected).findByText(
			"No explanation, evidence or feedback is available for this observation.",
		);
		expect(within(selected).queryByText("Next step")).toBeNull();
		expect(within(selected).queryByText(/PracticeCatalogLoader/u)).toBeNull();
	});

	it("keeps a failed observation read retryable and replaces it with the complete detail after recovery", async () => {
		let reads = 0;
		server.use(
			http.get(
				"*/workspaces/:workspaceSlug/practices/results/:resultId",
				() => {
					reads += 1;
					return HttpResponse.json({ status: 503, title: "Unavailable" }, { status: 503 });
				},
				{ once: true },
			),
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () => {
				reads += 1;
				return HttpResponse.json(completeObservation);
			}),
		);
		renderRouteAtWithRouter(observationPage());
		await screen.findByText("We could not load this observation", undefined, ROUTE_RENDER_WAIT);
		const failedReads = reads;
		expect(screen.queryByRole("list", { name: "Reviews" })).toBeNull();
		fireEvent.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		expect(reads).toBe(failedReads + 1);
	});

	it.each(["MET", "DOES_NOT_APPLY", "COULD_NOT_DETERMINE"] as const)(
		"keeps the complete explanation for %s without fabricating a problem",
		async (outcome) => {
			server.use(
				http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
					HttpResponse.json({ ...completeObservation, outcome, problems: [], severity: null }),
				),
			);
			renderRouteAtWithRouter(observationPage());
			const selected = await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
			assert(completeObservation.explanation !== null);
			await within(selected).findByText(completeObservation.explanation);
			expect(within(selected).queryByText("Problems")).toBeNull();
			expect(within(selected).queryByText("Next step")).toBeNull();
		},
	);
});

/** One piece of feedback on the page, about the practice the level opens. */
const feedback: PracticePageFeedback = {
	unverifiedHistory: false,
	id: "00000000-0000-0000-0000-000000000201",
	headline: "Pull requests bundle a fix with a refactor",
	body: "Reviewers had to follow two intentions in one diff.",
	nextStep: null,
	practiceSlug: practice.slug,
	practiceName: practice.name,
	groupSlug: packagingGroup.slug,
	groupName: packagingGroup.name,
	preparedAt: new Date("2026-09-09T14:10:00Z"),
	readAt: null,
	closedAt: null,
	closedBy: null,
	response: null,
	whyItMatters: null,
	whatGoodLooksLike: null,
	withdrawn: false,
	cleanNeeded: 3,
	cleanWork: [],
	evidence: [],
};

/** The practice's own picture and guide, as the server returns them for the open practice. */
const guidance = bundledGuidance satisfies PracticeGuidanceDto;

const PRACTICE_PAGE = `${PAGE}?detail=${encodeURIComponent(JSON.stringify([group, practiceEntry]))}`;

describe("the feedback an observation gave", () => {
	afterEach(() => {
		clearUserView();
		vi.restoreAllMocks();
	});

	// The address takes a card's id as a UUID, as the server writes them.
	const pageCard: PracticePageFeedback = {
		...feedback,
		id: "00000000-0000-4000-8000-000000000201",
	};

	/** The open observation, with one delivered piece at `destination`. */
	const withFeedback = (destination: "PRACTICE_PAGE" | "CONVERSATION") => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/results/:resultId", () =>
				HttpResponse.json({
					...completeObservation,
					feedback: [
						{
							id: pageCard.id,
							destination,
							state: "DELIVERED",
							deliveredAt: "2026-09-09T14:20:00Z",
							response: null,
							copies: [],
						},
					],
				}),
			),
			http.get("*/workspaces/:workspaceSlug/practices/feedback/practice-page", () =>
				HttpResponse.json([pageCard]),
			),
		);
	};

	it("opens its card on the Feedback tab, and Back finds the observation open again", async () => {
		withFeedback("PRACTICE_PAGE");
		const scrolledTo: string[] = [];
		vi.spyOn(Element.prototype, "scrollIntoView").mockImplementation(function (this: Element) {
			scrolledTo.push(this.textContent);
		});
		const { router } = renderRouteAtWithRouter(observationPage());
		const selected = await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		await within(selected).findByRole(
			"link",
			{ name: "Read it on this practice’s Feedback tab" },
			SETTLE_WAIT,
		);
		// The observation stands on its own until its history loads and draws its row again, so the
		// link is read once the history is the one list.
		await waitFor(() => expect(screen.getAllByRole("list", { name: "Reviews" })).toHaveLength(1));
		fireEvent.click(screen.getByRole("link", { name: "Read it on this practice’s Feedback tab" }));
		// The Feedback tab is the default, so the address leaves it out.
		await waitFor(() => {
			expect(screen.getByRole("tab", { name: /^Feedback/u }).getAttribute("aria-selected")).toBe(
				"true",
			);
		}, SETTLE_WAIT);
		// The card the link named scrolls into view as it arrives.
		await waitFor(() => expect(scrolledTo.join("\n")).toContain(pageCard.headline), SETTLE_WAIT);
		// The address lets go of the card on arrival, so a reload or a later visit does not mark it again.
		await waitFor(() => expect(router.state.location.search.feedbackCard).toBeUndefined());
		expect(router.state.location.search.observation).toBe(completeObservation.id);

		router.history.back();
		await waitFor(() => expect(router.state.location.search.practiceTab).toBe("results"));
		const back = await screen.findByRole("list", { name: "Reviews" }, SETTLE_WAIT);
		assert(completeObservation.explanation !== null);
		await within(back).findByText(completeObservation.explanation, undefined, SETTLE_WAIT);
	});

	it("links feedback raised in conversation to the conversation with Hephaestus", async () => {
		withFeedback("CONVERSATION");
		renderRouteAtWithRouter(observationPage());
		const selected = await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		const link = await within(selected).findByRole(
			"link",
			{ name: "Read it in your conversation" },
			SETTLE_WAIT,
		);
		expect(link.getAttribute("href")).toBe("/w/acme/conversation");
	});

	it("turns the observations' timeframe into the since the group's reviews ask for", async () => {
		const noon = new Date(2026, 8, 22, 12, 0);
		vi.useFakeTimers({ toFake: ["Date"] });
		vi.setSystemTime(noon);
		try {
			const asked: (string | null)[] = [];
			server.use(
				http.get(
					"*/workspaces/:workspaceSlug/practice-groups/:groupSlug/reviews",
					({ request }) => {
						asked.push(new URL(request.url).searchParams.get("since"));
						return HttpResponse.json(pageOf([groupReview]));
					},
				),
			);
			renderRouteAtWithRouter(`${PRACTICE_PAGE}&practiceTab=results&observationSince=30d`);
			await waitFor(
				() => expect(asked.at(-1)).toBe(reviewRangeStart(noon.getTime(), "30d").toISOString()),
				SETTLE_WAIT,
			);
		} finally {
			vi.useRealTimers();
		}
	});
});

describe("practice introduction", () => {
	let guidanceAsked: unknown[] = [];

	beforeEach(() => {
		guidanceAsked = [];
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/guidance", ({ params }) => {
				guidanceAsked.push(params.practiceSlug);
				return HttpResponse.json(guidance);
			}),
		);
	});
	it("asks for the guidance only for the practice that is open", async () => {
		const router = await renderProfile();
		expect(guidanceAsked).toStrictEqual([]);

		await openPractice(router);

		// The level opens on its feedback; the introduction and its picture are the Guide tab's.
		const tab = await screen.findByRole("tab", { name: "Guide" }, SETTLE_WAIT);
		expect(screen.queryByRole("region", { name: "Introduction" })).toBeNull();
		fireEvent.click(tab);
		const intro = await screen.findByRole("region", { name: "Introduction" }, SETTLE_WAIT);
		await within(intro).findByRole("img", { name: guidance.visual.alt }, SETTLE_WAIT);
		expect(guidanceAsked).toStrictEqual([practice.slug]);
	});

	it("opens a feedback card's Learn more on the practice's introduction and picture", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/feedback/practice-page", () =>
				HttpResponse.json([feedback]),
			),
		);
		const { router } = renderRouteAtWithRouter(PAGE);

		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Learn more about this practice" },
				ROUTE_RENDER_WAIT,
			),
		);

		const tab = await screen.findByRole("tab", { name: "Guide" }, SETTLE_WAIT);
		expect(tab.getAttribute("aria-selected")).toBe("true");
		await waitFor(() => expect(router.state.location.search.practiceTab).toBe("guide"));
		expect(stackInSearch(router.state.location.search.detail)).toContain(practiceEntry);
		const intro = await screen.findByRole("region", { name: "Introduction" }, SETTLE_WAIT);
		await within(intro).findByRole("img", { name: guidance.visual.alt }, SETTLE_WAIT);
	});

	it("opens the guide a link asks for, in the practice's Guide tab", async () => {
		renderRouteAtWithRouter(`${PRACTICE_PAGE}&practiceTab=guide`);
		await screen.findByRole("heading", { name: practice.name }, ROUTE_RENDER_WAIT);

		const tab = await screen.findByRole("tab", { name: "Guide" }, SETTLE_WAIT);
		expect(tab.getAttribute("aria-selected")).toBe("true");
		screen.getByRole("heading", { level: 4, name: "How to do it" });
	});

	it("shows a practice with no standing from its catalog words and guide, with no result made up", async () => {
		const sentence = "We pair on every schema change before it reaches review.";
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/reviewed", () =>
				HttpResponse.json([
					...reviewedPractices,
					{
						slug: "pair-on-schema-changes",
						name: "Pair on schema changes",
						group: null,
						whyItMatters: sentence,
						whatGoodLooksLike: null,
					} satisfies ReviewedPractice,
				]),
			),
			http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/guidance", () =>
				HttpResponse.json({
					...plainGuidance,
					practiceSlug: "pair-on-schema-changes",
					guide: guidance.guide,
				} satisfies PracticeGuidanceDto),
			),
		);
		renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify(["practice:pair-on-schema-changes"]))}`,
		);

		await screen.findByText(
			"This practice offers guidance without automated review.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		screen.getByText(sentence);
		screen.getByRole("heading", { name: "Pair on schema changes" });
		screen.getByRole("heading", { level: 4, name: "How to do it" });
		expect(screen.queryByRole("tab", { name: /^Observations/u })).toBeNull();
		// The page behind counts its own practices by standing; the level makes up none for this one.
		const level = screen.getByRole("dialog", { name: "Pair on schema changes" });
		expect(within(level).queryByText("Not reviewed")).toBeNull();
	});
});

/**
 * An accepted ask names the review it created. The reader's own detail of it answers before it
 * records anything — its own status, nothing listed yet — so the way to it is a link to their own
 * review, followed only when they choose.
 */
describe("the way to a review an accepted ask created", () => {
	const NEW_ID = "0f2b7c1e-9a3d-4c5b-8e1f-2d6a7b8c9d01";

	/** The server's answer for a review the reader just asked for, before it records anything. */
	function requestedDetail(item: ProfileReviewDetail["review"]["item"]): ProfileReviewDetail {
		const review: ProfileReviewDetail["review"] = {
			...openProfileReview.review,
			id: NEW_ID,
			item,
			origin: "REQUEST",
			status: "QUEUED",
			completedAt: null,
		};
		return {
			...openProfileReviewDetail,
			review,
			results: [],
			feedback: [],
			trace: { item, review, events: [], results: [] },
			practiceOutcomes: { met: 0, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 },
		};
	}

	/** Accepts every ask as the new review and serves that review; counts the asks and its reads. */
	function acceptAsks(item: ProfileReviewDetail["review"]["item"]) {
		const asked: unknown[] = [];
		let reads = 0;
		server.use(
			http.post("*/workspaces/:workspaceSlug/practices/review-requests", async ({ request }) => {
				asked.push(await request.json());
				return HttpResponse.json({ status: "SUBMITTED", reviewId: NEW_ID, reason: null });
			}),
			http.get(`*/workspaces/:workspaceSlug/practice-profile/reviews/${NEW_ID}`, () => {
				reads += 1;
				return HttpResponse.json(requestedDetail(item));
			}),
		);
		return { asked, reads: () => reads };
	}

	const STILL_EMPTY = "Observations appear here as the review reaches each practice.";

	it("answers a row with the new review opened over the list, and says it on no other work's review", async () => {
		const row = profileReviews.find((entry) => entry.review.item.label === "#890");
		assert(row, "The fixtures carry a review of #890");
		expect(row.review.item.id).not.toBe(openProfileReview.review.item.id);
		const { asked, reads } = acceptAsks(row.review.item);
		const router = await renderProfile();
		fireEvent.click(await openReviewsChip());
		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Request another review: #890" },
				ROUTE_RENDER_WAIT,
			),
		);

		const note = await screen.findByRole("note", undefined, SETTLE_WAIT);
		expect(asked).toStrictEqual([{ itemId: row.review.item.id }]);
		expect(stack(router)).toStrictEqual(["reviews:all"]);
		fireEvent.click(within(note).getByRole("link", { name: "View requested review" }));

		await waitFor(() => expect(stack(router)).toStrictEqual(["reviews:all", `review:${NEW_ID}`]));
		await screen.findByText(STILL_EMPTY, undefined, ROUTE_RENDER_WAIT);
		expect(reads()).toBeGreaterThan(0);
		await waitFor(() => expect(screen.queryByRole("note")).toBeNull());

		// One step over the list: Back closes it onto the list and its note.
		router.history.back();
		await waitFor(() => expect(stack(router)).toStrictEqual(["reviews:all"]));
		await screen.findByRole("note", undefined, SETTLE_WAIT);

		// A review of other work is not where this ask was made.
		fireEvent.click(
			await screen.findByRole("button", { name: openReviewRowName }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() =>
			expect(stack(router)).toStrictEqual(["reviews:all", `review:${openProfileReview.review.id}`]),
		);
		await screen.findByRole("table", { name: "Every practice in this review" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(screen.queryByRole("note")).toBeNull());
		expect(asked).toHaveLength(1);
	});
});

const responseControls = async () => {
	const router = await renderProfile();
	await screen.findByText(feedback.headline, undefined, ROUTE_RENDER_WAIT);
	const ratings = screen.queryAllByRole("button", { name: "Helpful" }).length;
	const answers = screen.queryAllByRole("button", { name: "I fixed this" }).length;
	await openPractice(router);
	await showResults();
	await screen.findAllByText(ownSummary, undefined, ROUTE_RENDER_WAIT);
	return {
		ratings,
		answers,
		responses: screen.queryAllByRole("group", { name: /^Your response to the feedback/u }).length,
	};
};

// User view is read-only; every response control must stay absent.
describe("practice profile in a user view", () => {
	beforeEach(() => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/feedback/practice-page", () =>
				HttpResponse.json([feedback]),
			),
		);
	});
	afterEach(clearUserView);

	it("offers the developer a rating and an answer on the card, and no response inside an observation", async () => {
		// The feedback is answered where it is; the observation only says where it went.
		await expect(responseControls()).resolves.toStrictEqual({
			ratings: 1,
			answers: 1,
			responses: 0,
		});
	});

	it("offers an administrator viewing as the developer no review to start", async () => {
		storeUserView({ workspaceSlug: "acme" });
		await renderProfile();
		fireEvent.click(await openReviewsChip());

		await screen.findByRole("button", { name: openReviewRowName }, ROUTE_RENDER_WAIT);
		expect(screen.queryAllByRole("button", { name: /^Request (?:another )?review/u })).toHaveLength(
			0,
		);
	});

	it("offers an administrator viewing as the developer none of them", async () => {
		storeUserView({ workspaceSlug: "acme" });
		await expect(responseControls()).resolves.toStrictEqual({
			ratings: 0,
			answers: 0,
			responses: 0,
		});
	});
});

const pullRequestEvidence = (number: number) => ({
	resultId: `0b9c6f0e-0000-4000-8000-0000000090${number}`,
	reviewId: `5d1e2a3b-0000-4000-8000-0000000090${number}`,
	recordedAt: "2026-09-08T10:00:00Z",
	summary: null,
	item: {
		kind: "scm.pull_request",
		id: String(9000 + number),
		parentId: null,
		label: `#${number}`,
		title: `Pull request ${number}`,
		provider: "GITHUB" as const,
		url: null,
		container: null,
		path: null,
		removedAt: null,
	},
});

/** A practice of the open group that every review so far found not to apply. */
const neverApplied = practiceStandings.find(
	(each) => each.groupSlug === packagingGroup.slug && each.slug !== practice.slug,
);
if (!neverApplied) {
	throw new Error("The fixtures carry a second practice in the packaging group");
}
const withNeverApplied = practiceStandings.map((each) =>
	each.slug === neverApplied.slug
		? { ...each, standing: "NO_OPPORTUNITY" as const, doesNotApplyOnly: true }
		: each,
);

/** Every practice of the packaging group never applied. */
const packagingNeverApplied = practiceStandings.map((each) =>
	each.groupSlug === packagingGroup.slug
		? { ...each, standing: "NO_OPPORTUNITY" as const, doesNotApplyOnly: true }
		: each,
);
/** The server reads such a group as NO_OPPORTUNITY. */
const packagingGroupNothingToReport = Object.values(groupStandings).map((each) =>
	each.groupSlug === packagingGroup.slug ? { ...each, standing: "NO_OPPORTUNITY" as const } : each,
);

describe("practice profile pattern label and folds", () => {
	const patterned = {
		...feedback,
		evidence: [pullRequestEvidence(21), pullRequestEvidence(22)],
	};
	const groupPage = `${PAGE}?detail=${encodeURIComponent(JSON.stringify([group]))}`;

	it("folds a practice that never applied under its group's table, with no standing yet", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/standings", () =>
				HttpResponse.json(withNeverApplied),
			),
		);
		const openNeverApplied = { name: `Open practice ${neverApplied.name}` };
		const fold = { name: "Practices with no standing yet" };

		renderRouteAtWithRouter(groupPage);
		const folded = await screen.findByRole("button", fold, ROUTE_RENDER_WAIT);
		expect(folded.getAttribute("aria-expanded")).toBe("false");
		expect(screen.queryByRole("button", openNeverApplied)).toBeNull();
		cleanup();

		// Its own level says the same, in place of "Nothing to report yet"; why stays behind the badge.
		renderRouteAtWithRouter(
			`${PAGE}?detail=${encodeURIComponent(JSON.stringify([group, `practice:${neverApplied.slug}`]))}`,
		);
		await expect(
			screen.findAllByRole("button", { name: "No standing yet" }, ROUTE_RENDER_WAIT),
		).resolves.not.toHaveLength(0);
		expect(screen.queryByText("Does not apply to your work yet")).toBeNull();
	});

	it("names a group whose every practice never applied as having no standing yet", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/standings", () =>
				HttpResponse.json(packagingNeverApplied),
			),
			http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () =>
				HttpResponse.json(packagingGroupNothingToReport),
			),
		);
		renderRouteAtWithRouter(groupPage);
		await expect(
			screen.findAllByRole("button", { name: "No standing yet" }, ROUTE_RENDER_WAIT),
		).resolves.not.toHaveLength(0);
		expect(screen.queryByText("Nothing to report yet")).toBeNull();
		expect(screen.queryByText("Does not apply to your work yet")).toBeNull();
	});

	it("shows the pattern as a plain label in the group's table", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/feedback/practice-page", () =>
				HttpResponse.json([patterned]),
			),
		);
		renderRouteAtWithRouter(groupPage);
		const label = await screen.findByText(/^Has open feedback/u, {}, ROUTE_RENDER_WAIT);
		expect(label.closest("button")).toBeNull();
	});
});

/** The overview of a member who has no recorded review yet, with the server's word on readiness. */
const dayOneOverview = (reviewReadiness: PracticeProfileOverview["reviewReadiness"]) =>
	http.get("*/workspaces/:workspaceSlug/practice-profile/overview", () =>
		HttpResponse.json({ ...OVERVIEW_FIXTURE, latestReview: null, reviewReadiness }),
	);

describe("a profile without feedback", () => {
	it("says what starts a first review, from the server's events", async () => {
		server.use(dayOneOverview(READY_REVIEW_READINESS));
		await renderProfile();
		await screen.findByText("No review of your work yet", undefined, ROUTE_RENDER_WAIT);
		screen.getByText(/ready for review$/u);
	});

	it("names why no review runs and opens the AI choice, returning to the profile", async () => {
		server.use(dayOneOverview({ ...READY_REVIEW_READINESS, blocker: "NO_AI" }));
		await renderProfile();
		const link = await screen.findByRole(
			"link",
			{ name: "Change your AI choice" },
			ROUTE_RENDER_WAIT,
		);
		const href = new URL(String(link.getAttribute("href")), "https://app.invalid");
		expect(href.pathname).toBe("/w/acme/onboarding");
		expect(href.searchParams.get("returnTo")).toBe("/w/acme/practice-profile");
		expect(screen.queryByText("No review of your work yet")).toBeNull();
	});
});
