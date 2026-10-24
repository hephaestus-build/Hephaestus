import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";
import { resultSummary } from "@/components/admin/practice-reviews/result-words";

import {
	feedbackDetail,
	itemTraceOf,
	practiceCounts,
	resultDetail,
	reviewDetail,
	reviewFeedback,
	reviewFeedbackChecksOf,
	reviewItem,
	reviewPracticeScripts,
	reviewResults,
	reviews,
} from "@/components/admin/practice-reviews/fixtures";
import {
	LINEAGE_RESULT,
	LINEAGE_REVIEW_ID,
	wholeReview,
} from "@/components/admin/practice-reviews/lineage-fixtures";
import { forgetTraceDisplay } from "@/components/admin/practice-reviews/trace-display";
import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import {
	reviewHandlers,
	feedbackCountHandlers,
} from "@/routes/_authenticated/w/$workspaceSlug/admin/practices/reviews/-reviews-server";
import { deferred, sleep } from "@/test/async";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 30_000 });

function readsOldReview(url: URL) {
	return (
		url.pathname.endsWith(`/practices/reviews/${LINEAGE_REVIEW_ID}`) ||
		url.searchParams.get("reviewId") === LINEAGE_REVIEW_ID
	);
}

function heldWorkspaceRead(until: Promise<void>) {
	return async () => {
		await until;
		return HttpResponse.json({});
	};
}

const REVIEWS = "/w/acme/admin/practices/reviews";
const REVIEW = "3f2b6c1e-8a4d-4f0b-9c7e-2d5a1b0c9e84";
const RESULT_ROW = "9a1d3c5e-7b2f-4e6a-8c0d-1f3b5d7e9a2c";
const FEEDBACK_ROW = "4c2e8a1b-6d3f-4a5c-9e7b-0f1d2c3b4a5e";
const RESULT = LINEAGE_RESULT.cache404;
const TRACE = `trace:review:${REVIEW}`;

const fixtureReview = reviews.find((review) => review.id === LINEAGE_REVIEW_ID);
const servedReview = { ...reviewDetail(LINEAGE_REVIEW_ID), id: REVIEW };
const ITEM = `item:${reviewItem.id}`;
const servedResult = resultDetail(RESULT);
const resultFeedbackLevels = servedResult.feedback.map((feedback) => `feedback:${feedback.id}`);
const [firstResultFeedback] = servedResult.feedback;
assert(firstResultFeedback);
const itemReview = reviews.find((review) => review.item.id === reviewItem.id);
assert(itemReview);
const itemReviewLevels = itemTraceOf(itemReview).reviews.map((review) => `review:${review.id}`);
const [fixtureResult] = reviewResults;
const [fixtureFeedback] = reviewFeedback;

function page(content: unknown[]) {
	return HttpResponse.json({ content, totalElements: content.length });
}

const requested: URL[] = [];
const record = ({ request }: { request: Request }) => {
	requested.push(new URL(request.url));
};

function requestsTo(pathname: string): URL[] {
	return requested.filter((url) => url.pathname === `/workspaces/acme${pathname}`);
}

beforeEach(() => {
	// jsdom needs an IntersectionObserver stub for paged lists.
	vi.stubGlobal("IntersectionObserver", ObserverStub);
	forgetTraceDisplay();
	requested.length = 0;
	server.events.on("request:start", record);
	server.use(
		// Every trace tells the whole review the fixture's records sit in.
		http.get("*/workspaces/:workspaceSlug/practices/reviews/lineage", () =>
			HttpResponse.json(wholeReview),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews", () =>
			page([{ ...fixtureReview, id: REVIEW }]),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/results", () =>
			page([{ ...fixtureResult, id: RESULT_ROW, reviewId: REVIEW }]),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", () =>
			page([{ ...fixtureFeedback, id: FEEDBACK_ROW, reviewId: REVIEW }]),
		),
		http.get(`*/workspaces/:workspaceSlug/practices/reviews/${REVIEW}`, () =>
			HttpResponse.json(servedReview),
		),
		http.get(`*/workspaces/:workspaceSlug/practices/reviews/${REVIEW}/feedback-checks`, () =>
			HttpResponse.json(reviewFeedbackChecksOf(servedReview)),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/results/:resultId", ({ params }) =>
			HttpResponse.json({ ...servedResult, id: String(params.resultId) }),
		),
		...feedbackCountHandlers(),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId", ({ params }) =>
			HttpResponse.json({
				...feedbackDetail(String(fixtureFeedback?.id)),
				id: String(params.feedbackId),
			}),
		),
		...reviewHandlers(),
	);
});

afterEach(() => {
	server.events.removeListener("request:start", record);
});

/** The trace in front: the wide sheet with the story of the record it was opened for. */
async function traceSheet() {
	const story = await screen.findByRole(
		"group",
		{ name: "How this came about" },
		ROUTE_RENDER_WAIT,
	);
	const sheet = story.closest<HTMLElement>('[role="dialog"]');
	assert(sheet);
	return sheet;
}

/** The addressed front level, after its parent has entered and its own heading is visible. */
async function frontSheet(levels: number) {
	return waitFor(() => {
		const sheet = document.querySelector<HTMLElement>(
			`[role="dialog"][data-detail-depth="${levels - 1}"]`,
		);
		assert(sheet);
		expect(within(sheet).getAllByRole("heading", { level: 2 }).length).toBeGreaterThan(0);
		return sheet;
	}, ROUTE_RENDER_WAIT);
}

/** The `detail` levels a link opens, as the router writes them: repeated, or one JSON list. */
function linkedLevels(url: URL): string[] {
	return url.searchParams
		.getAll("detail")
		.flatMap((raw) => stackInSearch(raw.startsWith("[") ? (JSON.parse(raw) as unknown) : raw));
}

/**
 * A sheet links out to the provider or to a setting, and inside Practice reviews only to its own
 * relatives: one result or problem level in its place (a result's problems, a problem's result,
 * the results and problems a feedback rests on), its own feedback or reviews, or one of its own
 * lists scoped to its review. It
 * never opens a trace: the wider history is told there, not on a sheet.
 */
function expectNoStrayRecordLinks(sheet: HTMLElement, relatedLevels: readonly string[] = []) {
	expect(strayRecordLinks(sheet, relatedLevels)).toStrictEqual([]);
	const renderedRelatedLevels = within(sheet)
		.queryAllByRole("link")
		.flatMap((link) =>
			linkedLevels(new URL(String(link.getAttribute("href")), "https://hephaestus.invalid")).slice(
				-1,
			),
		)
		.filter((level) => relatedLevels.includes(level));
	expect(new Set(renderedRelatedLevels)).toStrictEqual(new Set(relatedLevels));
}

/** The links of a sheet into Practice reviews that open neither a relative nor its own list. */
function strayRecordLinks(sheet: HTMLElement, relatedLevels: readonly string[]): string[] {
	return within(sheet)
		.queryAllByRole("link")
		.map((link) => String(link.getAttribute("href")))
		.filter((href) => {
			const url = new URL(href, "https://hephaestus.invalid");
			if (!url.pathname.includes("/admin/practices/reviews")) {
				return false;
			}
			// A trace may stay under the level a link opens, as the sheet's own layer; never in front.
			const front = linkedLevels(url).at(-1);
			return front === undefined
				? !url.searchParams.has("reviewId")
				: !/^(?:result|problem):/u.test(front) && !relatedLevels.includes(front);
		});
}

/** Escape where focus is, as a reader presses it. */
function pressEscape() {
	fireEvent.keyDown(document.activeElement ?? document.body, { key: "Escape" });
}

/** Where the reader started: the key of the step marked "You started here", whose control is current. */
function startedAt(sheet: HTMLElement): string | undefined {
	return sheet.querySelector<HTMLElement>('[data-step-key]:has(> [aria-current="step"])')?.dataset
		.stepKey;
}

describe("layer 0: a row's own record, Track and Trace", () => {
	it("opens a review's sheet from its row: the review alone, with no story and no record links", async () => {
		const { router } = renderRouteAtWithRouter(REVIEWS);
		const [open] = await screen.findAllByRole(
			"link",
			{ name: /^Open review of #/u },
			ROUTE_RENDER_WAIT,
		);
		assert(open);
		await userEvent.click(open);

		const sheet = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await waitFor(() =>
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
				expect.stringMatching(/^review:/u),
			]),
		);
		await within(sheet).findByRole("heading", { name: "State and history" }, ROUTE_RENDER_WAIT);
		within(sheet).getByRole("heading", { name: "Technical" });
		expect(within(sheet).queryByRole("group", { name: "How this came about" })).toBeNull();
		expectNoStrayRecordLinks(sheet);
	});

	it("tracks a result from its row: every table narrows to it, and the bar names it", async () => {
		const first = { ...fixtureResult, id: RESULT_ROW, reviewId: REVIEW };
		assert(fixtureResult);
		const { router } = renderRouteAtWithRouter(`${REVIEWS}/results`);
		// The loading table is replaced by the loaded one, so the row is found first and its table after.
		const track = await screen.findByRole(
			"button",
			{ name: `Track observation ${resultSummary(fixtureResult)}` },
			ROUTE_RENDER_WAIT,
		);
		expect(track.closest("table")?.getAttribute("aria-label")).toBe("Observations");
		await userEvent.click(track);

		await waitFor(() =>
			expect(router.state.location.search).toMatchObject({
				resultId: first.id,
				reviewId: first.reviewId,
				selected: true,
			}),
		);
		expect(router.state.location.search).not.toHaveProperty("detail");
		const bar = await screen.findByRole("group", { name: "Selection" }, ROUTE_RENDER_WAIT);
		within(bar).getByText("Tracking:");
		await within(bar).findByText(
			"A cache miss and a permission failure come back as the same 404",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		// The tracked row's toggle is pressed.
		await waitFor(() =>
			expect(
				// The list is read again for the narrowed scope, so the row is found afresh.
				screen
					.getByRole("button", {
						name: `Track observation ${resultSummary(fixtureResult)}`,
					})
					.getAttribute("aria-pressed"),
			).toBe("true"),
		);
		await waitFor(() =>
			expect(requestsTo("/practices/reviews/results").at(-1)?.searchParams.get("reviewId")).toBe(
				first.reviewId,
			),
		);
	});

	it("traces a review from its row: the trace opens and the review is tracked", async () => {
		const { router } = renderRouteAtWithRouter(REVIEWS);
		const [trace] = await screen.findAllByRole(
			"link",
			{ name: /^Trace review of #/u },
			ROUTE_RENDER_WAIT,
		);
		assert(trace);
		await userEvent.click(trace);

		await waitFor(() =>
			expect(router.state.location.search).toMatchObject({
				detail: TRACE,
				reviewId: REVIEW,
				selected: true,
			}),
		);
		const sheet = await traceSheet();
		sheet.querySelector("[data-trace-verdict]");
		expect(sheet.querySelector<HTMLElement>('[data-here="true"]')?.dataset.stepKey).toBe("review");
		within(sheet).getByText("You started here");
		// The tables behind it are narrowed to the review it traces.
		await screen.findByRole("group", { name: "Selection", hidden: true }, ROUTE_RENDER_WAIT);
	});

	it("traces a piece of feedback from its row, tracking it and its review", async () => {
		const { router } = renderRouteAtWithRouter(`${REVIEWS}/feedback`);
		// The loading table is replaced by the loaded one, so the row is found first and its table after.
		const [trace] = await screen.findAllByRole(
			"link",
			{ name: /^Trace feedback /u },
			ROUTE_RENDER_WAIT,
		);
		assert(trace);
		expect(trace.closest("table")?.getAttribute("aria-label")).toBe("Feedback");
		await userEvent.click(trace);

		await waitFor(() =>
			expect(router.state.location.search).toMatchObject({
				selected: true,
			}),
		);
		expect(router.state.location.search).toMatchObject({
			detail: `trace:feedback:${FEEDBACK_ROW}`,
			feedbackId: FEEDBACK_ROW,
			reviewId: REVIEW,
		});
		await traceSheet();
	});

	it("keeps Track and Trace out of the overview's Practices table", async () => {
		const [practice] = practiceCounts;
		assert(practice);
		renderRouteAtWithRouter(`${REVIEWS}/overview`);
		await screen.findByRole(
			"link",
			{ name: `Open practice ${practice.practiceName}` },
			ROUTE_RENDER_WAIT,
		);
		expect(
			screen.queryByRole("link", { name: `Trace practice ${practice.practiceName}` }),
		).toBeNull();
		expect(
			screen.queryByRole("button", { name: `Track practice ${practice.practiceName}` }),
		).toBeNull();
	});
});

describe("a review's sheet", () => {
	it("shows each phase of the run between the boundaries the server sent", async () => {
		assert(servedReview.runtime);
		const [job] = servedReview.runtime.jobs;
		assert(job);
		server.use(
			http.get(`*/workspaces/:workspaceSlug/practices/reviews/${REVIEW}`, () =>
				HttpResponse.json({
					...servedReview,
					status: "COMPLETE",
					runtime: {
						jobs: [
							{
								...job,
								attempt: 1,
								status: "COMPLETED",
								availableAt: "2026-07-28T13:34:48Z",
								startedAt: "2026-07-28T13:35:00Z",
								completedAt: "2026-07-28T13:40:00Z",
							},
						],
					},
				}),
			),
		);

		renderRouteAtWithRouter(`${REVIEWS}?detail=review:${REVIEW}`);
		const sheet = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		const execution = await within(sheet).findByRole(
			"list",
			{ name: "Execution" },
			ROUTE_RENDER_WAIT,
		);
		expect(execution.textContent).toContain("Wait to start: 12 seconds · Running: 5 minutes");
	});

	it("shows what this review's practice scripts did", async () => {
		const [practice, ...others] = servedReview.practices;
		assert(practice);
		server.use(
			http.get(`*/workspaces/:workspaceSlug/practices/reviews/${REVIEW}`, () =>
				HttpResponse.json({
					...servedReview,
					practices: [{ ...practice, script: reviewPracticeScripts.fromCode }, ...others],
				}),
			),
		);

		renderRouteAtWithRouter(`${REVIEWS}?detail=review:${REVIEW}`);
		const sheet = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await within(sheet).findByRole("region", { name: "Practice scripts" }, ROUTE_RENDER_WAIT);
	});
});

describe("the trace and a record over it", () => {
	it("opens a result's own feedback over the trace, Back restores it, and Escape closes each layer", async () => {
		const { router } = renderRouteAtWithRouter(`${REVIEWS}?detail=${TRACE}`);
		const sheet = await traceSheet();
		const dot = within(sheet).getByRole("button", {
			name: /A cache miss and a permission failure come back as the same 404/u,
		});
		await userEvent.click(dot);

		await waitFor(() =>
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
				TRACE,
				`result:${RESULT}`,
			]),
		);
		const over = await screen.findByRole(
			"dialog",
			{ name: "A cache miss and a permission failure come back as the same 404" },
			ROUTE_RENDER_WAIT,
		);
		expect(within(over).queryByRole("group", { name: "How this came about" })).toBeNull();
		expectNoStrayRecordLinks(over, resultFeedbackLevels);
		await within(over).findByRole("button", { name: "Mark incorrect" }, ROUTE_RENDER_WAIT);
		const [feedbackLink] = within(
			within(over).getByRole("list", { name: "Feedback written from this observation" }),
		).getAllByRole("link");
		assert(feedbackLink);
		await userEvent.click(feedbackLink);
		await waitFor(() =>
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
				TRACE,
				`feedback:${firstResultFeedback.id}`,
			]),
		);
		const feedbackSheet = await screen.findByRole("dialog", { name: /^For /u }, ROUTE_RENDER_WAIT);
		expect(requestsTo(`/practices/reviews/feedback/${firstResultFeedback.id}`)).not.toHaveLength(0);
		expect(within(feedbackSheet).queryByRole("group", { name: "How this came about" })).toBeNull();
		router.history.back();
		await waitFor(() =>
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
				TRACE,
				`result:${RESULT}`,
			]),
		);
		const restored = await screen.findByRole(
			"dialog",
			{ name: "A cache miss and a permission failure come back as the same 404" },
			ROUTE_RENDER_WAIT,
		);
		await within(restored).findByRole("button", { name: "Mark incorrect" }, ROUTE_RENDER_WAIT);

		pressEscape();
		await waitFor(
			() => expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([TRACE]),
			ROUTE_RENDER_WAIT,
		);
		await traceSheet();

		pressEscape();
		await waitFor(
			() => expect(router.state.location.search).not.toHaveProperty("detail"),
			ROUTE_RENDER_WAIT,
		);
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	});

	/**
	 * Closing both levels at once from the sheet's path goes back past both entries they were opened
	 * with, so the browser's Back afterwards does not open them again.
	 */
	it("closes a trace and the sheet over it from the path without Back reopening them", async () => {
		const { router } = renderRouteAtWithRouter(REVIEWS);
		const [trace] = await screen.findAllByRole(
			"link",
			{ name: /^Trace review of #/u },
			ROUTE_RENDER_WAIT,
		);
		assert(trace);
		await userEvent.click(trace);
		const sheet = await traceSheet();
		await userEvent.click(
			within(sheet).getByRole("button", {
				name: /A cache miss and a permission failure come back as the same 404/u,
			}),
		);
		const over = await screen.findByRole(
			"dialog",
			{ name: "A cache miss and a permission failure come back as the same 404" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(
			within(within(over).getByRole("list", { name: "Path" })).getByText("Practice reviews"),
		);
		await waitFor(
			() => expect(router.state.location.search).not.toHaveProperty("detail"),
			ROUTE_RENDER_WAIT,
		);

		router.history.back();
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		expect(router.state.location.search).not.toHaveProperty("detail");
	});

	it("keeps the mark where the reader started while a record opens over the trace", async () => {
		const { router } = renderRouteAtWithRouter(`${REVIEWS}?detail=${TRACE}`);
		const sheet = await traceSheet();
		expect(startedAt(sheet)).toBe("review");
		within(sheet).getByText("You started here");

		await userEvent.click(
			within(sheet).getByRole("button", {
				name: /A cache miss and a permission failure come back as the same 404/u,
			}),
		);
		await waitFor(() =>
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
				TRACE,
				`result:${RESULT}`,
			]),
		);
		expect(router.state.location.search).not.toHaveProperty("focus");
		expect(startedAt(sheet)).toBe("review");
		expect(sheet.querySelector<HTMLElement>('[data-here="true"]')?.dataset.stepKey).toBe("review");
	});

	it("keeps the mark where it started", async () => {
		renderRouteAtWithRouter(`${REVIEWS}?detail=${TRACE}`);
		const sheet = await traceSheet();
		await userEvent.click(within(sheet).getByRole("button", { name: "Expand all" }));
		expect(startedAt(sheet)).toBe("review");
	});

	it("keeps a sheet over the trace as the deepest level: an address with more keeps the front one", async () => {
		const { router } = renderRouteAtWithRouter(
			`${REVIEWS}?detail=${encodeURIComponent(
				JSON.stringify([TRACE, `review:${REVIEW}`, `result:${RESULT}`]),
			)}`,
		);
		await waitFor(
			() =>
				expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
					TRACE,
					`result:${RESULT}`,
				]),
			ROUTE_RENDER_WAIT,
		);
	});

	/**
	 * Another workspace and person, with its replies held: neither the last workspace's tab counts nor
	 * its chain stand in, and the last chain's review is not read under the new workspace.
	 */
	it("shows no counts or chain of the last workspace while the next one's replies are held", async () => {
		const OTHER = "/workspaces/other";
		const held = deferred<undefined>();
		server.use(
			http.get(`*${OTHER}/practices/reviews`, heldWorkspaceRead(held.promise)),
			http.get(`*${OTHER}/practices/reviews/results`, heldWorkspaceRead(held.promise)),
			http.get(`*${OTHER}/practices/reviews/feedback`, heldWorkspaceRead(held.promise)),
			http.get(`*${OTHER}/practices/reviews/lineage`, heldWorkspaceRead(held.promise)),
		);
		try {
			const { router } = renderRouteAtWithRouter(REVIEWS);
			await screen.findByRole("link", { name: "Reviews, 1" }, ROUTE_RENDER_WAIT);
			await act(async () => router.history.push(`${REVIEWS}?detail=${TRACE}`));
			within(await traceSheet()).getByText("You started here");
			await act(async () =>
				router.history.push(`/w/other/admin/practices/reviews?detail=${TRACE}&developerId=7`),
			);
			const otherReads = () => requested.filter((url) => url.pathname.startsWith(`${OTHER}/`));
			await waitFor(() => {
				expect(otherReads().some((url) => url.pathname.endsWith("/lineage"))).toBe(true);
				expect(
					otherReads().filter((url) => url.pathname === `${OTHER}/practices/reviews`),
				).not.toHaveLength(0);
			}, ROUTE_RENDER_WAIT);
			const tabs = document.querySelector('nav[aria-label="Practice review sections"]');
			assert(tabs);
			expect(tabs.querySelector('[aria-label^="Reviews,"]')).toBeNull();
			expect(tabs.querySelectorAll('[aria-label$=", 1"]')).toHaveLength(0);
			expect(screen.queryByText("You started here")).toBeNull();
			expect(otherReads().filter(readsOldReview)).toStrictEqual([]);
		} finally {
			await act(async () => held.resolve(undefined));
		}
	});
});

/** One of the trace's "Show" toggles. */
function showToggle(sheet: HTMLElement, name: string): HTMLElement {
	return within(within(sheet).getByRole("group", { name: "Show" })).getByRole("button", { name });
}

describe("what the trace shows", () => {
	it("offers one Show control", async () => {
		renderRouteAtWithRouter(`${REVIEWS}?detail=${TRACE}`);
		const sheet = await traceSheet();
		const pressed = ["Times", "System info", "Siblings", "Connections"].map((name) =>
			showToggle(sheet, name).getAttribute("aria-pressed"),
		);
		expect(pressed).toStrictEqual(["true", "false", "false", "false"]);
	});

	it("applies each toggle to the whole trace at once", async () => {
		renderRouteAtWithRouter(`${REVIEWS}?detail=${TRACE}`);
		const sheet = await traceSheet();
		const steps = within(sheet).getByRole("list", { name: "Steps, oldest first" });
		expect(steps.querySelectorAll("time").length).toBeGreaterThan(1);
		expect(sheet.querySelector("[data-step-facts]")).toBeNull();

		await userEvent.click(showToggle(sheet, "Times"));
		await waitFor(() => expect(steps.querySelectorAll("time")).toHaveLength(0));

		await userEvent.click(showToggle(sheet, "System info"));
		await waitFor(() =>
			expect(sheet.querySelector('[data-here="true"] [data-step-facts]')).not.toBeNull(),
		);
		expect(sheet.querySelector('[data-here="false"] [data-step-facts]')).toBeNull();
		await userEvent.click(within(sheet).getByRole("button", { name: "Expand all" }));
		for (const piece of steps.querySelectorAll('[data-step="feedback"]')) {
			expect(piece.querySelector("dl")).not.toBeNull();
		}

		await userEvent.click(showToggle(sheet, "Connections"));
		await waitFor(() =>
			expect(sheet.querySelectorAll("path[data-connector-line]").length).toBeGreaterThan(0),
		);
	});

	it("remembers the reader's choice for the next trace they open", async () => {
		renderRouteAtWithRouter(`${REVIEWS}?detail=${TRACE}`);
		const first = await traceSheet();
		await userEvent.click(showToggle(first, "Times"));
		await userEvent.click(showToggle(first, "Connections"));
		await waitFor(() =>
			expect(showToggle(first, "Connections").getAttribute("aria-pressed")).toBe("true"),
		);
		cleanup();

		renderRouteAtWithRouter(`${REVIEWS}/feedback?detail=trace:feedback:${FEEDBACK_ROW}`);
		const second = await traceSheet();
		expect(showToggle(second, "Times").getAttribute("aria-pressed")).toBe("false");
		expect(showToggle(second, "Connections").getAttribute("aria-pressed")).toBe("true");
	});

	it("starts from the default where storage is unavailable", async () => {
		const getItem = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
			throw new Error("Storage is blocked");
		});
		try {
			renderRouteAtWithRouter(`${REVIEWS}?detail=${TRACE}`);
			const sheet = await traceSheet();
			expect(showToggle(sheet, "Times").getAttribute("aria-pressed")).toBe("true");
		} finally {
			getItem.mockRestore();
		}
	});
});

describe("a record's sheet is the record alone", () => {
	/**
	 * The trace is the one place a record's story is told: no sheet, opened from a row or over a
	 * trace, renders a story, a verdict or a Trace action. It links only its own relatives.
	 */
	it.each([
		["review", `review:${REVIEW}`, []],
		["result", `result:${RESULT_ROW}`, resultFeedbackLevels],
		["feedback", `feedback:${FEEDBACK_ROW}`, []],
		["item", ITEM, itemReviewLevels],
		["practice", `practice:${String(practiceCounts[0]?.practiceSlug)}`, []],
	] as const)(
		"renders no story in the %s sheet, alone or over a trace",
		async (_kind, level, relatedLevels) => {
			for (const detail of [[level], [TRACE, level]]) {
				renderRouteAtWithRouter(`${REVIEWS}?detail=${encodeURIComponent(JSON.stringify(detail))}`);
				const sheet = await frontSheet(detail.length);
				await within(sheet).findAllByRole("heading", { level: 2 }, ROUTE_RENDER_WAIT);
				expect(sheet.querySelector("[data-trace-verdict]")).toBeNull();
				expect(sheet.querySelector("[data-step-key]")).toBeNull();
				expect(within(sheet).queryByRole("link", { name: /^Trace /u })).toBeNull();
				expect(within(sheet).queryByText("You started here")).toBeNull();
				expectNoStrayRecordLinks(sheet, relatedLevels);
				cleanup();
			}
		},
	);
});

/**
 * A sheet's body and footer as markup, without the ids React generates, which differ between two
 * renders of the same sheet.
 */
function sheetMarkup(sheet: HTMLElement): string {
	return (
		[
			// The title: a practice sheet's introduction has headings of its own.
			sheet.querySelector('[data-slot="drawer-title"]')?.textContent ?? "",
			sheet.querySelector('[data-slot="drawer-body"]')?.outerHTML ?? "",
			sheet.querySelector('[data-slot="drawer-footer"]')?.outerHTML ?? "",
		]
			.join("\n")
			.replaceAll(/ (?:id|for|aria-labelledby|aria-describedby|aria-controls)="[^"]*"/gu, "")
			// A link to a relative opens it on the sheet's own layer, so its address names that layer.
			.replaceAll(/ href="[^"]*detail=[^"]*"/gu, "")
	);
}

/** The sheet in front once it has settled: its record read and nothing left loading. */
async function settledSheet(detail: string[]): Promise<string> {
	renderRouteAtWithRouter(`${REVIEWS}?detail=${encodeURIComponent(JSON.stringify(detail))}`);
	const sheet = await frontSheet(detail.length);
	await within(sheet).findAllByRole("heading", { level: 2 }, ROUTE_RENDER_WAIT);
	await waitFor(
		() => expect(sheet.querySelector('[data-slot="skeleton"]')).toBeNull(),
		ROUTE_RENDER_WAIT,
	);
	// One more turn for the reads that land after the first ones, such as a review's verdicts.
	await sleep(300);
	const markup = sheetMarkup(sheet);
	cleanup();
	return markup;
}

describe("a sheet over the trace is the sheet from its row", () => {
	/**
	 * The same component, the same sections and the same reads: only the layer differs, which the
	 * header's path and its Back or Close say, and those are left out of the comparison.
	 */
	it.each([
		["review", `review:${REVIEW}`],
		["result", `result:${RESULT_ROW}`],
		["feedback", `feedback:${FEEDBACK_ROW}`],
		["item", ITEM],
		["practice", `practice:${String(practiceCounts[0]?.practiceSlug)}`],
	])("renders the %s sheet alike from its row and over a trace", async (_kind, level) => {
		const alone = await settledSheet([level]);
		const over = await settledSheet([TRACE, level]);
		expect(alone).toContain("data-sheet-section");
		expect(over).toBe(alone);
	});
});

describe("the trace of work no review started on", () => {
	const trace = itemTraceOf(servedReview);
	const [event] = trace.events;
	assert(event);
	const waiting = {
		...trace,
		reviews: [],
		events: [
			{
				...event,
				reviewId: null,
				state: "WAITING",
				reason: "PRACTICES_DISABLED",
			},
		],
	};

	it("asks for no chain, and says the work waits while reviews are on", async () => {
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
			),
			http.get("*/workspaces/:workspaceSlug/practices/items/:itemId/trace", () =>
				HttpResponse.json(waiting),
			),
		);
		renderRouteAtWithRouter(
			`${REVIEWS}?detail=${encodeURIComponent(JSON.stringify([`trace:${ITEM}`]))}`,
		);
		const sheet = await traceSheet();
		await waitFor(() =>
			expect(sheet.querySelector("[data-trace-verdict]")?.textContent).toBe(
				"No review has started on this work yet: it waits before a review starts.",
			),
		);
		expect(sheet.textContent).not.toMatch(/turned off/u);
		expect(requestsTo("/practices/reviews/lineage")).toHaveLength(0);
	});
});

describe("deep links", () => {
	it("opens a review's page address that focused a result as that result's trace, keeping the selection and the scope", async () => {
		const { router } = renderRouteAtWithRouter(
			`${REVIEWS}/review/${REVIEW}?focus=result:${RESULT}&reviewId=${REVIEW}&selected=true&practiceSlug=thin-controllers&from=2026-08-01&developerId=7`,
		);
		await waitFor(() => expect(router.state.location.pathname).toBe(REVIEWS), ROUTE_RENDER_WAIT);
		expect(router.state.location.search).toMatchObject({
			detail: `trace:result:${RESULT}`,
			reviewId: REVIEW,
			selected: true,
			practiceSlug: "thin-controllers",
			from: "2026-08-01",
			developerId: "7",
		});
		expect(router.state.location.search).not.toHaveProperty("focus");
		const sheet = await traceSheet();
		await waitFor(() => expect(startedAt(sheet)).toBe(`result:${RESULT}`));
	});

	it("opens an old review level as the review's sheet", async () => {
		const { router } = renderRouteAtWithRouter(`${REVIEWS}/results?detail=review:${REVIEW}`);
		const sheet = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await within(sheet).findByRole("heading", { name: "State and history" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.pathname).toBe(`${REVIEWS}/results`);
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([`review:${REVIEW}`]);
	});

	it("drops a step an old address still names", async () => {
		const { router } = renderRouteAtWithRouter(`${REVIEWS}?detail=review:${REVIEW}&focus=results`);
		await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		expect(router.state.location.search).not.toHaveProperty("focus");
	});
});
