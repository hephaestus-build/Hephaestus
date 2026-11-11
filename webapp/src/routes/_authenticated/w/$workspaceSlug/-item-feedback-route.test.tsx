import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";

import type { ProfileReview } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { detailFeedback, detailReview } from "@/stories/practice-detail-story-mock-data";
import { daysBefore } from "@/stories/story-clock";
import { ObserverStub } from "@/test/observers";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

beforeAll(() => loadRouteCode("/w/acme/feedback/1902"), ROUTE_RENDER_WAIT.timeout);

const REVIEWS_PATH = "*/workspaces/:workspaceSlug/practices/items/:itemId/reviews";
const RESPONSE_PATH = "*/workspaces/:workspaceSlug/practices/feedback/:feedbackId/response";

const ratedReview: ProfileReview = {
	...detailReview,
	feedback: [
		{
			...detailFeedback,
			response: {
				usefulness: "HELPFUL",
				resolution: null,
				comment: null,
				createdAt: daysBefore(1),
			},
		},
	],
};

let reads: { workspaceSlug: string; itemId: string }[] = [];

beforeEach(() => {
	reads = [];
	server.use(
		http.get(REVIEWS_PATH, ({ params }) => {
			reads.push({ workspaceSlug: String(params.workspaceSlug), itemId: String(params.itemId) });
			return HttpResponse.json({
				content: [ratedReview],
				page: 0,
				size: 10,
				totalElements: 1,
				totalPages: 1,
			});
		}),
	);
});

/** The reader's reviews of the work over two flat pages, one each. Returns the pages asked for. */
function serveTwoReviewPages(): string[] {
	const earlier: ProfileReview = {
		...ratedReview,
		review: { ...ratedReview.review, id: "00000000-0000-4000-8000-0000000009a1" },
	};
	const reviews = [ratedReview, earlier];
	const pages: string[] = [];
	server.use(
		http.get(REVIEWS_PATH, ({ request }) => {
			const number = Number(new URL(request.url).searchParams.get("page"));
			pages.push(String(number));
			return HttpResponse.json({
				content: reviews.slice(number, number + 1),
				page: number,
				size: 1,
				totalElements: 2,
				totalPages: 2,
			});
		}),
	);
	return pages;
}

describe("the page a comment on the work links to", () => {
	it.each(["scm.pull_request/902", "scm.issue/902", "scm.repository/902"])(
		"shows no page for an address that names no item ID: %s",
		async (tail) => {
			renderRouteAt(`/w/acme/feedback/${tail}`);
			await screen.findByRole(
				"heading",
				{ name: "We could not find this page" },
				ROUTE_RENDER_WAIT,
			);
			expect(screen.getByRole("link", { name: "Go to the home page" }).getAttribute("href")).toBe(
				"/",
			);
			expect(reads).toHaveLength(0);
		},
	);

	it("reads the reader's reviews of exactly the linked item", async () => {
		renderRouteAt("/w/acme/feedback/1902");

		await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		screen.getByRole("heading", { name: "Your feedback on pull request #902" });
		expect(reads[0]).toStrictEqual({ workspaceSlug: "acme", itemId: "1902" });
	});

	/** The endpoint answers with a flat Spring page; reading it as anything else ends the feed at one page. */
	it("loads the earlier reviews of the work on request", async () => {
		// The observer never fires, so the press is what asks for the next page.
		vi.stubGlobal("IntersectionObserver", ObserverStub);
		onTestFinished(() => {
			vi.unstubAllGlobals();
		});
		const pages = serveTwoReviewPages();
		renderRouteAt("/w/acme/feedback/1902");

		const more = await screen.findByRole(
			"button",
			{ name: "View earlier reviews" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(more);

		await waitFor(() => expect(pages).toStrictEqual(["0", "1"]));
		await waitFor(() =>
			expect(screen.queryByRole("button", { name: "View earlier reviews" })).toBeNull(),
		);
	});

	it("writes a disagreement with its reason, keeps the rating, and reads the reviews again", async () => {
		const written: unknown[] = [];
		server.use(
			http.put(RESPONSE_PATH, async ({ request }) => {
				const body = await request.json();
				written.push(body);
				return HttpResponse.json(body);
			}),
		);
		renderRouteAt("/w/acme/feedback/1902");

		await userEvent.click(
			await screen.findByRole("button", { name: "Disagree" }, ROUTE_RENDER_WAIT),
		);
		await userEvent.type(
			screen.getByRole("textbox", { name: "Why do you disagree?" }),
			"The loader never ran on this path.",
		);
		await userEvent.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => {
			expect(written).toHaveLength(1);
		});
		expect(written[0]).toStrictEqual({
			usefulness: "HELPFUL",
			resolution: "DISAGREE",
			comment: "The loader never ran on this path.",
		});
		await waitFor(() => {
			expect(reads.length).toBeGreaterThan(1);
		});
	});

	it("clears the response through the endpoint's delete", async () => {
		const cleared: string[] = [];
		server.use(
			http.delete(RESPONSE_PATH, ({ params }) => {
				cleared.push(String(params.feedbackId));
				return HttpResponse.json({});
			}),
		);
		renderRouteAt("/w/acme/feedback/1902");

		await userEvent.click(
			await screen.findByRole("button", { name: "Clear response" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() => {
			expect(cleared).toStrictEqual([detailFeedback.id]);
		});
	});

	it("does not ask for anything when the address names no item", async () => {
		renderRouteAt("/w/acme/feedback/not-a-number");

		await screen.findByRole("heading", { name: "We could not find this page" }, ROUTE_RENDER_WAIT);
		expect(reads).toHaveLength(0);
	});
});
