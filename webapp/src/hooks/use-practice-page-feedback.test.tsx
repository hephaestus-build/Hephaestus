import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { FeedbackResponse, PracticePageFeedback } from "@/api/types.gen";
import {
	nextRating,
	nextResolution,
	usePracticePageFeedback,
} from "@/hooks/use-practice-page-feedback";
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

	it("keeps a disagreement and its reason when the rating is withdrawn", () => {
		expect(
			nextRating(
				{ usefulness: "UNHELPFUL", resolution: "DISAGREE", comment: "This is not what happened" },
				"UNHELPFUL",
			),
		).toStrictEqual({
			usefulness: undefined,
			resolution: "DISAGREE",
			comment: "This is not what happened",
		});
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

	it("replaces a disagreement, so the card closes on the answer the reader gave last", () => {
		expect(
			nextResolution(
				{ usefulness: "UNHELPFUL", resolution: "DISAGREE", comment: "The rename was its own PR" },
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

function practicePageFeedback(
	response?: Partial<Wire<FeedbackResponse>>,
): Wire<PracticePageFeedback> {
	return {
		id: feedbackId,
		unverifiedHistory: false,
		headline: "Pull requests bundle a fix with a refactor",
		body: "Reviewers had to follow two intentions in one diff.",
		practiceSlug: "scope-one-reviewable-change",
		practiceName: "Scope the change to one concern",
		preparedAt: "2026-09-09T14:10:00Z",
		readAt: null,
		closedAt: null,
		closedBy: null,
		groupName: null,
		groupSlug: null,
		nextStep: null,
		whatGoodLooksLike: null,
		whyItMatters: null,
		withdrawn: false,
		cleanNeeded: 3,
		cleanWork: [],
		evidence: [],
		response: response ?? null,
	};
}

function renderFeedback(
	response?: Partial<Wire<FeedbackResponse>>,
	enabled?: boolean,
	reread?: Promise<void>,
) {
	const written: { method: string; body: unknown }[] = [];
	const reads: string[] = [];
	server.use(
		http.get(
			"*/workspaces/:workspaceSlug/practices/feedback/practice-page",
			async ({ request }) => {
				reads.push(request.url);
				if (reads.length > 1) {
					await reread;
				}
				return HttpResponse.json([practicePageFeedback(response)]);
			},
		),
		http.put(
			"*/workspaces/:workspaceSlug/practices/feedback/:feedbackId/response",
			async ({ request }) => {
				written.push({ method: "PUT", body: await request.json() });
				return HttpResponse.json({});
			},
		),
		http.delete("*/workspaces/:workspaceSlug/practices/feedback/:feedbackId/response", () => {
			written.push({ method: "DELETE", body: undefined });
			return HttpResponse.json({});
		}),
	);
	const client = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	const { result } = renderHook(
		() => usePracticePageFeedback({ workspaceSlug, groups: [], enabled }),
		{
			wrapper: ({ children }: { children: ReactNode }) => (
				<QueryClientProvider client={client}>{children}</QueryClientProvider>
			),
		},
	);
	return { result, written, reads };
}

describe("usePracticePageFeedback", () => {
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

		await waitFor(() => expect(reads).toHaveLength(2));
		expect(written).toHaveLength(1);
		expect(result.current.ratingProps(feedbackId)).toMatchObject({
			usefulness: "HELPFUL",
			openBand: "comment",
			isPending: true,
		});
		reread.resolve();
		await waitFor(() => expect(result.current.ratingProps(feedbackId).isPending).toBe(false));
	});

	it("names the control that wrote only once the write outlasts the delay, and clears it when it settles", async () => {
		// Advance timeout-only fake timers while network reads and waits use real time.
		vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"], shouldAdvanceTime: true });
		try {
			const reread = deferred();
			const { result } = renderFeedback(undefined, true, reread.promise);
			await vi.waitFor(() => expect(result.current.cards).toHaveLength(1));

			act(() => {
				result.current.ratingProps(feedbackId).onResolve?.("ADDRESSED");
			});
			await vi.waitFor(() => expect(result.current.ratingProps(feedbackId).isPending).toBe(true));
			expect(result.current.ratingProps(feedbackId).saving).toBeUndefined();

			await act(async () => {
				await vi.advanceTimersByTimeAsync(1000);
			});
			expect(result.current.ratingProps(feedbackId).saving).toBe("answer");

			reread.resolve();
			await vi.waitFor(() => expect(result.current.ratingProps(feedbackId).isPending).toBe(false));
			await act(async () => {
				await vi.advanceTimersByTimeAsync(500);
			});
			expect(result.current.ratingProps(feedbackId).saving).toBeUndefined();
		} finally {
			vi.useRealTimers();
		}
	});

	it("writes the answer over a disagreement and shows it until the cards have been read again", async () => {
		const reread = deferred();
		const { result, written, reads } = renderFeedback(
			{ usefulness: "UNHELPFUL", resolution: "DISAGREE", comment: "The rename was its own PR" },
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

		expect(result.current.state).toStrictEqual({ status: "ready" });
		expect(result.current.cards).toStrictEqual([]);
		await sleep(0);
		expect(reads).toStrictEqual([]);
	});

	it("withdraws the response when the rating already chosen is pressed again", async () => {
		const { result, written } = renderFeedback({ usefulness: "HELPFUL" });
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onRate?.("HELPFUL");
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]?.method).toBe("DELETE");
	});

	it("sends the comment with the rating it was written under", async () => {
		const { result, written } = renderFeedback({ usefulness: "UNHELPFUL" });
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onSendComment?.("  Already do this  ");
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]).toStrictEqual({
			method: "PUT",
			body: { usefulness: "UNHELPFUL", comment: "Already do this" },
		});
	});

	it("opens no note band for a rating while a disagreement stands, so the reason stays the comment", async () => {
		const { result, written } = renderFeedback({
			usefulness: "HELPFUL",
			resolution: "DISAGREE",
			comment: "The rename was its own PR",
		});
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onRate?.("UNHELPFUL");
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]).toStrictEqual({
			method: "PUT",
			body: {
				usefulness: "UNHELPFUL",
				resolution: "DISAGREE",
				comment: "The rename was its own PR",
			},
		});
		expect(result.current.ratingProps(feedbackId).openBand).toBeUndefined();
	});

	it("opens the disagreement band on Disagree and writes the disagreement with its reason on Send", async () => {
		const { result, written } = renderFeedback({ usefulness: "HELPFUL" });
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onDisagree?.();
		});
		expect(result.current.ratingProps(feedbackId).openBand).toBe("disagreement");
		expect(written).toHaveLength(0);

		act(() => {
			result.current.ratingProps(feedbackId).onSendDisagreement?.("  #17 already split it  ");
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]).toStrictEqual({
			method: "PUT",
			body: { usefulness: "HELPFUL", resolution: "DISAGREE", comment: "#17 already split it" },
		});
		expect(result.current.ratingProps(feedbackId).openBand).toBeUndefined();
	});

	it("takes a standing disagreement back on Disagree, keeping the rating", async () => {
		const { result, written } = renderFeedback({
			usefulness: "UNHELPFUL",
			resolution: "DISAGREE",
			comment: "The rename was its own PR",
		});
		await waitFor(() => expect(result.current.cards).toHaveLength(1));

		act(() => {
			result.current.ratingProps(feedbackId).onDisagree?.();
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]).toStrictEqual({ method: "PUT", body: { usefulness: "UNHELPFUL" } });
	});
});
