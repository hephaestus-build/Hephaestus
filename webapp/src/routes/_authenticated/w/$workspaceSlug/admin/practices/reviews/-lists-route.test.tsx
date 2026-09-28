import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

const emptyPage = { content: [], page: { number: 0, size: 25, totalElements: 0, totalPages: 0 } };

/**
 * Repeated params and comma-joined params both, because which one the generated client emits is its
 * business and an assertion that guesses wrong passes or fails for the wrong reason.
 */
function values(url: URL | undefined, name: string): string[] {
	return (url?.searchParams.getAll(name) ?? [])
		.flatMap((value) => value.split(","))
		.filter(Boolean);
}

/**
 * Every request the three list routes make, with the review, observation and feedback URLs recorded.
 * The screens they feed take their rows as props, so a story mounts them without ever issuing the
 * request — this route test is the only place that can see what went on the wire.
 */
function recordRequests() {
	const reviewUrls: URL[] = [];
	const observationUrls: URL[] = [];
	const feedbackUrls: URL[] = [];
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/members", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/observations", ({ request }) => {
			observationUrls.push(new URL(request.url));
			return HttpResponse.json(emptyPage);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", ({ request }) => {
			feedbackUrls.push(new URL(request.url));
			return HttpResponse.json(emptyPage);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/reviews", ({ request }) => {
			reviewUrls.push(new URL(request.url));
			return HttpResponse.json(emptyPage);
		}),
	);
	return { reviewUrls, observationUrls, feedbackUrls };
}

describe("practice review list routes", () => {
	/**
	 * The one wire detail these screens can get wrong silently. The URL spells the ordering `order`,
	 * because another route already owns the word `sort` in the same search namespace with entirely
	 * different values; the endpoint spells it `sort`. A list ordered by the server looks exactly like
	 * a list ordered by the server's default, so nothing but the request itself can catch a rename —
	 * and this is the surface that exists to put the observations most worth acting on at the top.
	 */
	it("asks the endpoint for the ordering the URL chose, under the name the endpoint uses", async () => {
		const { observationUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/observations?order=ACTIONABILITY&severity=["MAJOR"]',
		);
		await screen.findByText("No observations match these filters", undefined, ROUTE_RENDER_WAIT);

		const requested = observationUrls.at(-1);
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
			'/w/acme/admin/practices/reviews/feedback?order=OLDEST&deliveryState=["AWAITING_APPROVAL"]',
		);
		await screen.findByText("No feedback matches these filters", undefined, ROUTE_RENDER_WAIT);

		const requested = feedbackUrls.at(-1);
		expect(requested?.searchParams.get("sort")).toBe("OLDEST");
		expect(requested?.searchParams.get("order")).toBeNull();
		expect(values(requested, "deliveryState")).toStrictEqual(["AWAITING_APPROVAL"]);
	});

	/** The default ordering is the server's, so nothing is sent rather than a guess at its name. */
	it("sends no ordering when the reader has not chosen one", async () => {
		const { observationUrls } = recordRequests();

		renderRouteAtWithRouter("/w/acme/admin/practices/reviews/observations");
		await screen.findByText("No observations yet", undefined, ROUTE_RENDER_WAIT);

		expect(observationUrls.at(-1)?.searchParams.get("sort")).toBeNull();
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
		const { observationUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/observations?outcome=["NEGATIVE"]&invalidated=true',
		);
		await screen.findByText("No observations match these filters", undefined, ROUTE_RENDER_WAIT);

		const requested = observationUrls.at(-1);
		expect(values(requested, "outcome")).toStrictEqual(["NEGATIVE"]);
		expect(requested?.searchParams.get("invalidated")).toBe("true");
	});

	/**
	 * The URL carries withholding *families*, which is the question an operator asks; the endpoint
	 * filters on individual reasons. The expansion happens on the way to the request, so a family that
	 * stopped expanding would return everything and read as a filter that simply matched a lot.
	 */
	it("expands a withholding family to the reasons the endpoint filters on", async () => {
		const { feedbackUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/feedback?withheldFamily=["HOUSEKEEPING"]',
		);
		await screen.findByText("No feedback matches these filters", undefined, ROUTE_RENDER_WAIT);

		const reasons = values(feedbackUrls.at(-1), "suppressionReason");
		expect(reasons).toContain("COMPOSER_DEDUPED");
		expect(reasons).not.toContain("HOUSEKEEPING");
		expect(feedbackUrls.at(-1)?.searchParams.get("withheldFamily")).toBeNull();
	});

	/** Live, requested and backfilled reviews are separate populations; the origin travels as asked. */
	it("sends the origin filter to the endpoint", async () => {
		const { observationUrls } = recordRequests();

		renderRouteAtWithRouter(
			'/w/acme/admin/practices/reviews/observations?origin=["MANUAL","BACKFILL"]',
		);
		await screen.findByText("No observations match these filters", undefined, ROUTE_RENDER_WAIT);

		expect(values(observationUrls.at(-1), "origin")).toStrictEqual(["MANUAL", "BACKFILL"]);
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

	/** A completed review whose results failed to process is only found by this filter. */
	it("sends the result-processing filter to the reviews endpoint", async () => {
		const { reviewUrls } = recordRequests();

		renderRouteAtWithRouter('/w/acme/admin/practices/reviews/runs?resultProcessing=["FAILED"]');
		await screen.findByText("No reviews found", undefined, ROUTE_RENDER_WAIT);

		expect(values(reviewUrls.at(-1), "resultProcessing")).toStrictEqual(["FAILED"]);
	});
});
