import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import {
	getPracticeReviewOverviewQueryKey,
	listPracticeReviewObservationsQueryKey,
	listPracticeReviewsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { reviewJob } from "@/components/admin/practice-reviews/fixtures";
import { reviewHandlers } from "@/components/admin/practice-reviews/story-mock-server";
import { hasText } from "@/lib/text";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter, testQueryClient } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 20_000 });

const COMPLETED_RUN = "11111111-1111-1111-1111-111111111111";
const RUNNING_RUN = "aaaaaaaa-8888-8888-8888-888888888888";

/**
 * The level opened by its URL over the review list, which reads neither observations nor feedback,
 * so every such request below is the level's own.
 */
const reviewLevel = (id: string) => `/w/acme/admin/practices/reviews/runs?detail=review:${id}`;

const requested: string[] = [];
const record = ({ request }: { request: Request }) => {
	requested.push(request.url);
};

function stub(...extra: Parameters<typeof server.use>) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		...extra,
		...reviewHandlers({ requireObservationSort: "ACTIONABILITY" }),
	);
}

function urlFor(path: string): URL | undefined {
	const found = requested.find((url) => new URL(url).pathname.endsWith(path));
	return hasText(found) ? new URL(found) : undefined;
}

beforeEach(() => {
	requested.length = 0;
	server.events.on("request:start", record);
});

afterEach(() => {
	server.events.removeListener("request:start", record);
});

describe("review level", () => {
	/**
	 * The one wire detail this screen can get wrong in silence. It shows five observations out of
	 * however many a review recorded, so it has to *ask* for the ordering that puts the ones worth
	 * acting on first — the endpoint's default is newest-first, and re-sorting five rows in the
	 * browser orders the five that happened to arrive rather than the five that matter. Nothing on
	 * the page looks different when the parameter goes missing.
	 */
	it("asks for the observations most worth acting on, five of each", async () => {
		stub();

		renderRouteAtWithRouter(reviewLevel(COMPLETED_RUN));
		await screen.findByRole(
			"heading",
			{ name: "Cache the workspace member lookup on the review path" },
			ROUTE_RENDER_WAIT,
		);

		const observations = urlFor("/practices/reviews/observations");
		expect(observations?.searchParams.get("sort")).toBe("ACTIONABILITY");
		expect(observations?.searchParams.get("size")).toBe("5");
		expect(observations?.searchParams.get("agentJobId")).toBe(COMPLETED_RUN);

		const feedback = urlFor("/practices/reviews/feedback");
		expect(feedback?.searchParams.get("size")).toBe("5");
		expect(feedback?.searchParams.get("agentJobId")).toBe(COMPLETED_RUN);
	});

	/**
	 * Cancelling answers with the run as it now stands, and that answer is written straight into the
	 * page rather than refetched: a reader who just stopped a review must not be shown it running.
	 * Everything that counted it running — the overview, the review list under other filters — is
	 * re-read, in this workspace only.
	 */
	it("shows the cancelled run the moment the server confirms it, and refreshes its counts", async () => {
		stub(
			http.post("*/workspaces/:workspaceSlug/agents/jobs/:jobId/cancel", () =>
				HttpResponse.json({ ...reviewJob(RUNNING_RUN), status: "CANCELLED" }),
			),
		);

		const queryClient = testQueryClient();
		const countsOf = (workspaceSlug: string) => {
			const path = { workspaceSlug };
			return [
				getPracticeReviewOverviewQueryKey({
					path,
					query: { from: new Date("2026-01-01T00:00:00Z"), zone: "UTC" },
				}),
				listPracticeReviewsQueryKey({ path, query: { status: ["RUNNING"] } }),
				listPracticeReviewObservationsQueryKey({ path, query: { agentJobId: RUNNING_RUN } }),
			];
		};
		const ours = countsOf("acme");
		const theirs = countsOf("other");
		for (const key of [...ours, ...theirs]) {
			queryClient.setQueryData(key, {});
		}
		renderRouteAtWithRouter(reviewLevel(RUNNING_RUN), queryClient);
		// Within the level: the list under it has running reviews of its own.
		const level = await screen.findByRole("dialog", {}, ROUTE_RENDER_WAIT);
		const trigger = await within(level).findByRole(
			"button",
			{ name: "Cancel review" },
			ROUTE_RENDER_WAIT,
		);
		await within(level).findByText("Running", {}, ROUTE_RENDER_WAIT);

		await userEvent.click(trigger);
		// The dialog's confirm carries the same words as the trigger that opened it.
		const buttons = await screen.findAllByRole("button", { name: "Cancel review" });
		const confirm = buttons.at(-1);
		assert(confirm);
		await userEvent.click(confirm);

		await within(level).findByText("Cancelled", {}, ROUTE_RENDER_WAIT);
		expect(
			requested.some((url) => new URL(url).pathname.endsWith(`/agents/jobs/${RUNNING_RUN}/cancel`)),
		).toBe(true);
		const invalidated = (keys: typeof ours) =>
			keys.map((key) => queryClient.getQueryState(key)?.isInvalidated);
		expect(invalidated(ours)).toStrictEqual([true, true, true]);
		expect(invalidated(theirs)).toStrictEqual([false, false, false]);
	});
});
