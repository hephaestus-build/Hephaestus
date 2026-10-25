import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";

import type { ProfileReview } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { detailFeedback, detailReview } from "@/stories/practice-detail-story-mock-data";
import { daysBefore } from "@/stories/story-clock";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Route mounting loads the app shell and lazy modules, so the initial render needs a longer test timeout.
vi.setConfig({ testTimeout: 20_000 });

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
				number: 0,
				size: 10,
				totalElements: 1,
				totalPages: 1,
				last: true,
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
				number,
				size: 1,
				totalElements: 2,
				totalPages: 2,
				last: number >= 1,
			});
		}),
	);
	return pages;
}

describe("the page a comment on the work links to", () => {
	it.each([
		["scm.pull_request", "902"],
		["scm.issue", "902"],
		["scm.pull_request", "9007199254740993"],
	])("resolves an already-posted %s link before reading history", async (kind, sourceId) => {
		const resolutions: string[] = [];
		server.use(
			http.get(
				"*/workspaces/:workspaceSlug/practices/items/legacy-feedback/:artifactKind/:artifactId",
				({ params }) => {
					resolutions.push(
						`${String(params.workspaceSlug)}/${String(params.artifactKind)}/${String(params.artifactId)}`,
					);
					return HttpResponse.json({ itemId: "1902" });
				},
			),
		);
		renderRouteAt(`/w/acme/feedback/${kind}/${sourceId}`);
		await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		expect(resolutions).toStrictEqual([`acme/${kind}/${sourceId}`]);
		expect(reads[0]).toStrictEqual({ workspaceSlug: "acme", itemId: "1902" });
	});

	it("keeps unknown legacy work unavailable without guessing its item ID", async () => {
		server.use(
			http.get(
				"*/workspaces/:workspaceSlug/practices/items/legacy-feedback/:artifactKind/:artifactId",
				() => HttpResponse.json({ status: 404 }, { status: 404 }),
			),
		);
		renderRouteAt("/w/acme/feedback/scm.pull_request/902");
		await screen.findByRole("heading", { name: "We could not find this page" }, ROUTE_RENDER_WAIT);
		expect(reads).toHaveLength(0);
	});

	it.each(["scm.repository/902", "scm.pull_request/0", "scm.issue/9223372036854775808"])(
		"refuses unsupported legacy address %s",
		async (tail) => {
			renderRouteAt(`/w/acme/feedback/${tail}`);
			await screen.findByRole(
				"heading",
				{ name: "We could not find this page" },
				ROUTE_RENDER_WAIT,
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
