import { focusManager, type QueryClient } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { getReviewQueueQueryKey } from "@/api/@tanstack/react-query.gen";
import type { ReviewQueue as ReviewQueueAnswer } from "@/api/types.gen";
import {
	busyReviewQueue,
	idleReviewQueue,
	practiceReviewOverview,
	reviews,
} from "@/components/admin/practice-reviews/fixtures";
import { QUEUE_POLL_MS, type QueueState } from "@/components/admin/practice-reviews/review-queue";
import { server } from "@/mocks/server";
import { reviewHandlers } from "@/routes/_authenticated/w/$workspaceSlug/admin/practices/reviews/-reviews-server";
import { ObserverStub } from "@/test/observers";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

beforeAll(
	() =>
		loadRouteCode("/w/acme/admin/practices/reviews", "/w/acme/admin/practices/reviews/overview"),
	ROUTE_RENDER_WAIT.timeout,
);

// jsdom needs an IntersectionObserver stub for paged lists.
beforeEach(() => {
	// Query polling uses intervals; router transitions and HTTP completion keep their real timers.
	vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
	vi.stubGlobal("IntersectionObserver", ObserverStub);
});

const OVERVIEW = "/w/acme/admin/practices/reviews/overview";

/** The overview with nothing queued or running, so only the queue itself can keep the poll going. */
const quietOverview = {
	...practiceReviewOverview,
	reviews: { ...practiceReviewOverview.reviews, queued: 0, running: 0 },
};

/** Answers each read of the queue with the next answer, the last one for good, and counts the reads. */
function serveQueue(...answers: ReviewQueueAnswer[]) {
	let reads = 0;
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/reviews/overview", () =>
			HttpResponse.json(quietOverview),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/queue", () => {
			const answer = answers[Math.min(reads, answers.length - 1)];
			reads += 1;
			return HttpResponse.json(answer);
		}),
		...reviewHandlers(),
	);
	return { reads: () => reads };
}

async function poll(queryClient: QueryClient) {
	const query = { queryKey: getReviewQueueQueryKey({ path: { workspaceSlug: "acme" } }) };
	await act(async () => {
		await vi.advanceTimersByTimeAsync(QUEUE_POLL_MS);
		await queryClient.getQueryCache().find(query)?.promise;
	});
}

afterEach(() => {
	focusManager.setFocused(undefined);
	vi.useRealTimers();
});

describe("practice review queue on the overview", () => {
	it("re-reads the queue while reviews wait and stops once it is empty", async () => {
		const queue = serveQueue(busyReviewQueue, busyReviewQueue, idleReviewQueue);
		const { queryClient } = renderRouteAtWithRouter(OVERVIEW);

		await screen.findByRole("link", { name: /^See all/u }, ROUTE_RENDER_WAIT);
		await poll(queryClient);
		expect(queue.reads()).toBe(2);
		await poll(queryClient);
		await screen.findByText("Nothing waits or runs.", undefined, ROUTE_RENDER_WAIT);
		expect(queue.reads()).toBe(3);

		await poll(queryClient);
		expect(queue.reads()).toBe(3);
	});

	it("does not poll an empty queue", async () => {
		const queue = serveQueue(idleReviewQueue);
		const { queryClient } = renderRouteAtWithRouter(OVERVIEW);

		await screen.findByText("Nothing waits or runs.", undefined, ROUTE_RENDER_WAIT);
		await poll(queryClient);
		expect(queue.reads()).toBe(1);
	});

	it("pauses while the browser tab is hidden and resumes when it is back", async () => {
		const queue = serveQueue(busyReviewQueue);
		const { queryClient } = renderRouteAtWithRouter(OVERVIEW);

		await screen.findByRole("link", { name: /^See all/u }, ROUTE_RENDER_WAIT);
		await poll(queryClient);
		expect(queue.reads()).toBe(2);

		act(() => focusManager.setFocused(false));
		await poll(queryClient);
		expect(queue.reads()).toBe(2);

		act(() => focusManager.setFocused(true));
		await poll(queryClient);
		await waitFor(() => expect(queue.reads()).toBeGreaterThan(2));
	});
});

/** Where each review's job stands, as the server keeps it: the first seven reviews, in line. */
const QUEUE_STATES_IN_LINE: QueueState[] = [
	"RUNNING",
	"RUNNING",
	"WAITING",
	"WAITING",
	"WAITING",
	"WAITING",
	"HELD",
];
const QUEUE_STATE_OF = new Map<string, QueueState>(
	QUEUE_STATES_IN_LINE.map((state, index) => [reviews[index]?.id ?? "", state]),
);

/** How many reviews stand in one queue state, as the server counts them. */
const countOf = (state: QueueState) =>
	QUEUE_STATES_IN_LINE.filter((other) => other === state).length;

/**
 * The queue and the Reviews list from the same states: the queue's figures counted from them, the
 * list filtered by `queueState` from them, as the server does.
 */
function serveQueueAndList() {
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/reviews/overview", () =>
			HttpResponse.json(quietOverview),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/queue", () =>
			HttpResponse.json({
				...busyReviewQueue,
				waiting: countOf("WAITING"),
				running: countOf("RUNNING"),
				heldForBudget: countOf("HELD"),
			}),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews", ({ request }) => {
			const url = new URL(request.url);
			const state = url.searchParams.get("queueState");
			const size = Number(url.searchParams.get("size") ?? 25);
			const content = reviews
				.filter((review) => state === null || QUEUE_STATE_OF.get(review.id) === state)
				.slice(0, size);
			return HttpResponse.json({
				content,
				page: 0,
				size,
				totalElements: content.length,
				totalPages: 1,
			});
		}),
		...reviewHandlers(),
	);
}

describe("the queue's figures", () => {
	it.each([
		["4 waiting", 4, "4 reviews match your filters.", "Waiting"],
		["2 running", 2, "2 reviews match your filters.", "Running"],
		[
			"Needs a look: 1 on hold for budget",
			1,
			"1 review matches your filters.",
			"On hold for budget",
		],
	])("%s opens as many reviews as it counts", async (name, count, total, pill) => {
		serveQueueAndList();
		renderRouteAtWithRouter(OVERVIEW);

		await userEvent.click(await screen.findByRole("link", { name }, ROUTE_RENDER_WAIT));

		const list = await screen.findByRole("region", { name: "Practice reviews" }, ROUTE_RENDER_WAIT);
		// Text and DOM queries only: role queries over the whole list outlast the test in jsdom.
		await within(list).findByText(total, undefined, ROUTE_RENDER_WAIT);
		// The filter is visible and removable, so the list says why it holds only these.
		within(list).getByText(`Queue now: ${pill}`);
		const rows = [...list.querySelectorAll("tbody tr")].filter(
			(row) => row.querySelector("[colspan]") === null,
		);
		expect(rows).toHaveLength(count);
	});
});
