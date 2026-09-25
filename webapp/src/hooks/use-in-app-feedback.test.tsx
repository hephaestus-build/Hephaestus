import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import type { FeedbackResponse, InAppFeedback } from "@/api/types.gen";
import { nextRating, nextResolution, useInAppFeedback } from "@/hooks/use-in-app-feedback";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { deferred, sleep } from "@/test/async";

describe("nextRating", () => {
	it("keeps the resolution and the comment when a rating replaces another", () => {
		expect(
			nextRating(
				{ usefulness: "HELPFUL", resolution: "ADDRESSED", comment: "Fixed it" },
				"UNHELPFUL",
			),
		).toStrictEqual({ usefulness: "UNHELPFUL", resolution: "ADDRESSED", comment: "Fixed it" });
	});

	it("withdraws the dispute with the rating, so the request never carries a comment-less dispute", () => {
		expect(
			nextRating(
				{ usefulness: "UNHELPFUL", resolution: "DISPUTED", comment: "This is not what happened" },
				"UNHELPFUL",
			),
		).toStrictEqual({ usefulness: undefined, resolution: undefined, comment: undefined });
	});

	it("keeps a resolution that stands on its own when the rating is withdrawn", () => {
		expect(
			nextRating(
				{ usefulness: "HELPFUL", resolution: "ADDRESSED", comment: "Fixed it" },
				"HELPFUL",
			),
		).toStrictEqual({ usefulness: undefined, resolution: "ADDRESSED", comment: undefined });
	});
});

describe("nextResolution", () => {
	it("keeps the rating and the comment when an answer is chosen", () => {
		expect(
			nextResolution({ usefulness: "HELPFUL", comment: "Split it in two" }, "ADDRESSED"),
		).toStrictEqual({ usefulness: "HELPFUL", resolution: "ADDRESSED", comment: "Split it in two" });
	});

	it("replaces a dispute, so the card closes on the answer the reader gave last", () => {
		expect(
			nextResolution(
				{ usefulness: "UNHELPFUL", resolution: "DISPUTED", comment: "The rename was its own PR" },
				"NOT_APPLICABLE",
			),
		).toStrictEqual({
			usefulness: "UNHELPFUL",
			resolution: "NOT_APPLICABLE",
			comment: "The rename was its own PR",
		});
	});

	it("takes the answer back when it is pressed again, keeping the rest", () => {
		expect(
			nextResolution(
				{ usefulness: "HELPFUL", resolution: "ADDRESSED", comment: "Split it in two" },
				"ADDRESSED",
			),
		).toStrictEqual({ usefulness: "HELPFUL", resolution: undefined, comment: "Split it in two" });
	});
});

const workspaceSlug = "acme";
const feedbackId = "scope-one-concern";

/** One piece of feedback as the wire sends it, carrying whatever response the case starts from. */
function inAppFeedback(response?: Omit<Wire<FeedbackResponse>, "feedbackId">): Wire<InAppFeedback> {
	return {
		id: feedbackId,
		headline: "Pull requests bundle a fix with a refactor",
		body: "Reviewers had to follow two intentions in one diff.",
		practiceSlug: "scope-one-reviewable-change",
		practiceName: "Scope the change to one concern",
		preparedAt: "2026-09-09T14:10:00Z",
		cleanNeeded: 3,
		cleanWork: [],
		evidence: [],
		response: response && { ...response, feedbackId },
	};
}

/**
 * The hook against the real client: what a press writes is a request body, and the four
 * transitions differ only in that body. `reads` counts the GETs, because reading the cards is
 * what delivers them.
 */
function renderFeedback(
	response?: Omit<Wire<FeedbackResponse>, "feedbackId">,
	enabled?: boolean,
	/** Holds every read after the first open until it settles. */
	reread?: Promise<void>,
) {
	const written: { method: string; body: unknown }[] = [];
	const reads: string[] = [];
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/feedback/in-app", async ({ request }) => {
			reads.push(request.url);
			if (reads.length > 1) {
				await reread;
			}
			return HttpResponse.json([inAppFeedback(response)]);
		}),
		http.put(
			"*/workspaces/:workspaceSlug/practices/feedback/:feedbackId/response",
			async ({ request }) => {
				written.push({ method: "PUT", body: await request.json() });
				return HttpResponse.json({});
			},
		),
		http.delete("*/workspaces/:workspaceSlug/practices/feedback/:feedbackId/response", () => {
			written.push({ method: "DELETE", body: undefined });
			return new HttpResponse(null, { status: 204 });
		}),
	);
	const client = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	const { result } = renderHook(() => useInAppFeedback({ workspaceSlug, groups: [], enabled }), {
		wrapper: ({ children }: { children: ReactNode }) => (
			<QueryClientProvider client={client}>{children}</QueryClientProvider>
		),
	});
	return { result, written, reads };
}

describe("useInAppFeedback", () => {
	it("writes the pressed rating, keeping what the reader said before", async () => {
		const { result, written } = renderFeedback();
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onRate?.("HELPFUL");
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]).toStrictEqual({
			method: "PUT",
			body: { usefulness: "HELPFUL" },
		});
	});

	it("shows the rating being written until the cards have been read again", async () => {
		const reread = deferred();
		const { result, written, reads } = renderFeedback(
			{ usefulness: "UNHELPFUL" },
			true,
			reread.promise,
		);
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onRate?.("HELPFUL");
		});

		// The write has landed and the cards are being read again, still carrying the rating it
		// replaced: the buttons wait on the pressed rating, so "Saving…" and the comment band sit
		// under the one the reader chose.
		await waitFor(() => expect(reads).toHaveLength(2));
		expect(written).toHaveLength(1);
		expect(result.current.ratingProps(feedbackId)).toMatchObject({
			usefulness: "HELPFUL",
			commentOpen: true,
			isPending: true,
		});
		reread.resolve();
		await waitFor(() => expect(result.current.ratingProps(feedbackId).isPending).toBe(false));
	});

	it("writes the answer over a dispute and shows it until the cards have been read again", async () => {
		const reread = deferred();
		const { result, written, reads } = renderFeedback(
			{ usefulness: "UNHELPFUL", resolution: "DISPUTED", comment: "The rename was its own PR" },
			true,
			reread.promise,
		);
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onResolve?.("ADDRESSED");
		});

		await waitFor(() => expect(reads).toHaveLength(2));
		expect(written).toStrictEqual([
			{
				method: "PUT",
				body: {
					usefulness: "UNHELPFUL",
					resolution: "ADDRESSED",
					comment: "The rename was its own PR",
				},
			},
		]);
		expect(result.current.ratingProps(feedbackId)).toMatchObject({
			usefulness: "UNHELPFUL",
			resolution: "ADDRESSED",
			isPending: true,
		});
		reread.resolve();
		await waitFor(() => expect(result.current.ratingProps(feedbackId).isPending).toBe(false));
	});

	it("withdraws the response when the answer standing alone is pressed again", async () => {
		const { result, written } = renderFeedback({ resolution: "NOT_APPLICABLE" });
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onResolve?.("NOT_APPLICABLE");
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]?.method).toBe("DELETE");
	});

	it("settles empty and asks for nothing while it is not enabled", async () => {
		const { result, reads } = renderFeedback(undefined, false);

		// A query held back by `enabled` stays pending, which the page would draw as a skeleton
		// that never resolves; the hook reports it settled instead.
		expect(result.current.state).toStrictEqual({ status: "ready" });
		expect(result.current.cards).toStrictEqual([]);
		// Reading the cards delivers them, so a held-back hook must not read them.
		await sleep(0);
		expect(reads).toStrictEqual([]);
	});

	it("withdraws the response when the rating already chosen is pressed again", async () => {
		const { result, written } = renderFeedback({ usefulness: "HELPFUL" });
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onRate?.("HELPFUL");
		});

		// Nothing is left of the response, so the write is the endpoint's delete, not an empty body.
		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]?.method).toBe("DELETE");
	});

	it("sends the comment with the rating it was written under", async () => {
		const { result, written } = renderFeedback({ usefulness: "UNHELPFUL" });
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current
				.ratingProps(feedbackId)
				.onSendComment?.({ reason: "not-useful", comment: "  Already do this  " });
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]).toStrictEqual({
			method: "PUT",
			body: { usefulness: "UNHELPFUL", comment: "Already do this" },
		});
	});

	it("disputes the feedback when the reason is that it is not accurate", async () => {
		const { result, written } = renderFeedback({ usefulness: "UNHELPFUL" });
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current
				.ratingProps(feedbackId)
				.onSendComment?.({ reason: "not-accurate", comment: "The rename was its own PR" });
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]).toStrictEqual({
			method: "PUT",
			body: {
				usefulness: "UNHELPFUL",
				resolution: "DISPUTED",
				comment: "The rename was its own PR",
			},
		});
	});
});
