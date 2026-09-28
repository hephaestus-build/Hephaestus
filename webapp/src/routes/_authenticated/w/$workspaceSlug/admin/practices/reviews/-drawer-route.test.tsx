import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import {
	practiceCounts,
	reviewFeedbackDetail,
	reviewObservations,
	reviewRuns,
} from "@/components/admin/practice-reviews/fixtures";
import { reviewHandlers } from "@/components/admin/practice-reviews/story-mock-server";
import { browserTimeZone } from "@/lib/dates";
import { server } from "@/mocks/server";
import { levelsOpenedBy } from "@/test/detail-stack";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 20_000 });

const REVIEWS = "/w/acme/admin/practices/reviews";

const requested: URL[] = [];
const record = ({ request }: { request: Request }) => {
	requested.push(new URL(request.url));
};

/** Every request to one endpoint so far, oldest first. */
function requestsTo(pathname: string): URL[] {
	return requested.filter((url) => url.pathname === `/workspaces/acme${pathname}`);
}

/** A repeatable param's values, whether the client repeated it or joined it with commas. */
function values(url: URL | undefined, name: string): string[] {
	return (url?.searchParams.getAll(name) ?? []).flatMap((value) => value.split(","));
}

function instant(url: URL | undefined, name: string): number | undefined {
	const value = url?.searchParams.get(name);
	return value == null ? undefined : new Date(value).getTime();
}

/** One review that timed out, for a request that asks for those; any other falls through. */
function timedOutWhenAsked(url: URL) {
	if (!values(url, "status").includes("TIMED_OUT")) {
		return;
	}
	const [finished] = reviewRuns;
	return HttpResponse.json({
		content: [
			{
				...finished,
				id: "cccccccc-8888-8888-8888-888888888888",
				status: "TIMED_OUT",
				target: { ...finished?.target, title: "Split the invoice export into pages" },
			},
		],
		page: { number: 0, size: 5, totalElements: 1, totalPages: 1 },
	});
}

beforeEach(() => {
	requested.length = 0;
	server.events.on("request:start", record);
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		...reviewHandlers(),
	);
});

afterEach(() => {
	server.events.removeListener("request:start", record);
});

describe("practice review levels", () => {
	/**
	 * A row opens its record over the list rather than instead of it, so closing the record is the
	 * list exactly as the reader left it — filters and all.
	 */
	it("opens an observation over its filtered list and closes back to that list", async () => {
		const observation = reviewObservations.find((row) => row.severity === "MAJOR");
		assert(observation);
		const { router } = renderRouteAtWithRouter(`${REVIEWS}/observations?severity=["MAJOR"]`);

		await userEvent.click(
			await screen.findByRole("link", { name: observation.summary }, ROUTE_RENDER_WAIT),
		);

		const level = await screen.findByRole(
			"dialog",
			{ name: observation.summary },
			ROUTE_RENDER_WAIT,
		);
		within(level).getByRole("heading", { name: observation.summary });
		expect(router.state.location.search).toMatchObject({
			severity: ["MAJOR"],
			detail: [`observation:${observation.id}`],
		});

		fireEvent.keyDown(document.body, { key: "Escape" });

		await waitFor(
			() => expect(router.state.location.search).not.toHaveProperty("detail"),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.pathname).toBe(`${REVIEWS}/observations`);
		expect(router.state.location.search).toMatchObject({ severity: ["MAJOR"] });
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		screen.getByRole("link", { name: observation.summary });
	});

	/**
	 * Feedback and its observations link to each other, so the stack is a graph: following the link
	 * back to a level already open closes down to it instead of stacking a copy of it on top. The
	 * level closed down to was opened by the URL, not by this visit, so dismissing it leaves the
	 * stack rather than stepping back into the observation.
	 */
	it("closes down to the feedback an observation links back to, and then out of the stack", async () => {
		const [source] = reviewFeedbackDetail.observations;
		assert(source);
		const feedbackKey = `feedback:${reviewFeedbackDetail.id}`;
		// Over the Reviews tab, so the page's crumb cannot be mistaken for the feedback level's.
		const { router } = renderRouteAtWithRouter(`${REVIEWS}/runs?detail=${feedbackKey}`);

		const feedbackLevel = await screen.findByRole(
			"dialog",
			{ name: /^Feedback for/u },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(
			await within(feedbackLevel).findByRole("link", { name: source.summary }, ROUTE_RENDER_WAIT),
		);

		const observationLevel = await screen.findByRole(
			"dialog",
			{ name: source.summary },
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.search.detail).toStrictEqual([
			feedbackKey,
			`observation:${source.observationId}`,
		]);
		const path = within(observationLevel).getByRole("list", { name: "Path" });
		expect(
			within(path)
				.getAllByRole("listitem")
				.map((crumb) => crumb.textContent),
		).toStrictEqual(["Practice reviews", "Feedback", "Observation"]);

		const feedbackLinks = await within(observationLevel).findAllByRole("link", {
			name: /^Feedback (?:about|this observation supports)/u,
		});
		const backToFeedback = feedbackLinks.find(
			(link) => JSON.stringify(levelsOpenedBy(link)) === JSON.stringify([feedbackKey]),
		);
		assert(backToFeedback, "The observation does not link back to the feedback it fed");
		await userEvent.click(backToFeedback);

		await waitFor(
			() => expect(router.state.location.search.detail).toStrictEqual([feedbackKey]),
			ROUTE_RENDER_WAIT,
		);
		await waitFor(() => expect(screen.getAllByRole("dialog")).toHaveLength(1));
		screen.getByRole("dialog", { name: /^Feedback for/u });

		fireEvent.keyDown(document.body, { key: "Escape" });

		await waitFor(
			() => expect(router.state.location.search).not.toHaveProperty("detail"),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.pathname).toBe(`${REVIEWS}/runs`);
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	});

	/**
	 * The overview asks for its range in the reader's own days — the zone decides where a day's
	 * bucket starts — and a count on it opens the list over the same days, so the list shows exactly
	 * the rows the reader clicked.
	 */
	it("opens a count's list over the range the overview counted", async () => {
		const { router } = renderRouteAtWithRouter(REVIEWS);

		await userEvent.click(
			await screen.findByRole("link", { name: "27 improvements" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(
			() => expect(router.state.location.pathname).toBe(`${REVIEWS}/observations`),
			ROUTE_RENDER_WAIT,
		);
		await waitFor(() => expect(requestsTo("/practices/reviews/observations")).not.toHaveLength(0));

		const overview = requestsTo("/practices/reviews/overview").at(0);
		const list = requestsTo("/practices/reviews/observations").at(-1);
		expect(overview?.searchParams.get("zone")).toBe(browserTimeZone());
		// The reader's midnight, as an instant: the server buckets from exactly this moment.
		const from = instant(overview, "from");
		assert(from !== undefined);
		const start = new Date(from);
		expect([start.getHours(), start.getMinutes(), start.getSeconds()]).toStrictEqual([0, 0, 0]);
		expect(instant(list, "from")).toBe(from);
		expect(values(list, "outcome")).toStrictEqual(["NEGATIVE"]);
	});

	/**
	 * A decision owed does not expire with the range, so the approvals ask for every piece of
	 * feedback awaiting one; what failed is asked for over the range the page shows.
	 */
	it("asks for every decision owed, and for failures over the range", async () => {
		renderRouteAtWithRouter(REVIEWS);
		await screen.findByRole("heading", { name: "Needs you" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(requestsTo("/practices/reviews")).not.toHaveLength(0));

		const overviewFrom = instant(requestsTo("/practices/reviews/overview").at(0), "from");
		const feedbackReads = requestsTo("/practices/reviews/feedback");
		const approvals = feedbackReads.find(
			(url) => values(url, "deliveryState").join(",") === "AWAITING_APPROVAL",
		);
		const failedDeliveries = feedbackReads.find((url) =>
			values(url, "deliveryState").includes("FAILED"),
		);
		const failedReviews = requestsTo("/practices/reviews").find((url) =>
			values(url, "status").includes("FAILED"),
		);

		expect(approvals?.searchParams.has("from")).toBe(false);
		expect(approvals?.searchParams.has("to")).toBe(false);
		expect(instant(failedDeliveries, "from")).toBe(overviewFrom);
		expect(failedDeliveries?.searchParams.has("to")).toBe(true);
		expect(instant(failedReviews, "from")).toBe(overviewFrom);
		expect(failedReviews?.searchParams.has("to")).toBe(true);
	});

	/** A review that ran out of time did not finish either, so it is owed a look like a failed one. */
	it("lists a review that timed out among the failed reviews that need you", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/reviews", ({ request }) =>
				timedOutWhenAsked(new URL(request.url)),
			),
		);
		renderRouteAtWithRouter(REVIEWS);

		const failed = await screen.findByRole("list", { name: "Failed reviews" }, ROUTE_RENDER_WAIT);
		within(failed).getByRole("link", { name: "Split the invoice export into pages" });
		const asked = requestsTo("/practices/reviews").find((url) =>
			values(url, "status").includes("TIMED_OUT"),
		);
		expect(values(asked, "status")).toHaveLength(2);
		expect(values(asked, "status")).toStrictEqual(expect.arrayContaining(["FAILED", "TIMED_OUT"]));
	});

	/**
	 * A practice opens its level over the overview, and the level asks for the practice's
	 * observations most worth acting on over the overview's range: the endpoint's default order is
	 * newest first, and five rows re-sorted in the browser are the five that arrived, not the five
	 * that matter.
	 */
	it("opens a practice from the overview and asks for its observations worth acting on", async () => {
		const [practice] = practiceCounts;
		assert(practice);
		const { router } = renderRouteAtWithRouter(REVIEWS);

		await userEvent.click(
			await screen.findByRole("link", { name: practice.practiceName }, ROUTE_RENDER_WAIT),
		);

		await screen.findByRole("dialog", { name: practice.practiceName }, ROUTE_RENDER_WAIT);
		expect(router.state.location.search.detail).toStrictEqual([
			`practice:${practice.practiceSlug}`,
		]);
		await waitFor(() => expect(requestsTo("/practices/reviews/observations")).not.toHaveLength(0));
		const observations = requestsTo("/practices/reviews/observations").at(-1);
		expect(values(observations, "practiceSlug")).toStrictEqual([practice.practiceSlug]);
		expect(observations?.searchParams.get("sort")).toBe("ACTIONABILITY");
		expect(observations?.searchParams.get("size")).toBe("5");
		expect(instant(observations, "from")).toBe(
			instant(requestsTo("/practices/reviews/overview").at(0), "from"),
		);
	});

	/**
	 * A piece of work has no record of its own to read: its level is what every review of it
	 * produced, so both reads are narrowed to the work and ask for the preview's five.
	 */
	it("asks both endpoints for a piece of work's first five", async () => {
		renderRouteAtWithRouter(`${REVIEWS}/runs?detail=work:pull-request:42`);
		await screen.findByRole("dialog", {}, ROUTE_RENDER_WAIT);

		const reads = await waitFor(() => {
			const found = [
				requestsTo("/practices/reviews/observations").at(-1),
				requestsTo("/practices/reviews/feedback").at(-1),
			];
			expect(found).not.toContain(undefined);
			return found;
		});
		for (const url of reads) {
			expect(url?.searchParams.get("artifactKind")).toBe("scm.pull_request");
			expect(url?.searchParams.get("artifactId")).toBe("42");
			expect(url?.searchParams.get("size")).toBe("5");
		}
	});
});
