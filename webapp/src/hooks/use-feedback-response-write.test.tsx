import { QueryClient, QueryClientProvider, type QueryKey } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import {
	getOwnPracticePageFeedbackQueryKey,
	getOwnResultQueryKey,
	getReviewFeedbackQueryKey,
	getOwnPracticeProfileOverviewQueryKey,
	listOwnGroupReviewsInfiniteQueryKey,
	listOwnItemReviewsInfiniteQueryKey,
	listOwnResultsQueryKey,
	listOwnReviewsInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { FeedbackResponse } from "@/api/types.gen";
import { useFeedbackResponseWrite } from "@/hooks/use-feedback-response-write";
import { server } from "@/mocks/server";
import { deferred, sleep } from "@/test/async";

const workspaceSlug = "acme";
const feedbackId = "00000000-0000-0000-0000-000000000103";
const RESPONSE_PATH = "*/workspaces/:workspaceSlug/practices/feedback/:feedbackId/response";

const recorded = (response: Partial<FeedbackResponse>): FeedbackResponse => ({
	createdAt: new Date("2026-09-09T14:10:00Z"),
	...response,
});

function renderWrite(held?: Promise<void>) {
	const written: { method: string; feedbackId: string; body: unknown }[] = [];
	server.use(
		http.put(RESPONSE_PATH, async ({ request, params }) => {
			const body = await request.json();
			written.push({ method: "PUT", feedbackId: String(params.feedbackId), body });
			await held;
			return HttpResponse.json(body);
		}),
		http.delete(RESPONSE_PATH, ({ params }) => {
			written.push({ method: "DELETE", feedbackId: String(params.feedbackId), body: undefined });
			return HttpResponse.json({});
		}),
	);
	const client = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	const { result } = renderHook(() => useFeedbackResponseWrite(workspaceSlug), {
		wrapper: ({ children }: { children: ReactNode }) => (
			<QueryClientProvider client={client}>{children}</QueryClientProvider>
		),
	});
	return { result, written, client };
}

describe("useFeedbackResponseWrite", () => {
	it("keeps the resolution and its comment when the usefulness changes", async () => {
		const { result, written } = renderWrite();

		act(() => {
			result.current.write(
				feedbackId,
				recorded({ resolution: "ADDRESSED", comment: "Split into two pull requests." }),
				{ usefulness: "HELPFUL" },
			);
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]).toStrictEqual({
			method: "PUT",
			feedbackId,
			body: {
				usefulness: "HELPFUL",
				resolution: "ADDRESSED",
				comment: "Split into two pull requests.",
			},
		});
	});

	it("keeps the usefulness when the resolution changes", async () => {
		const { result, written } = renderWrite();

		act(() => {
			result.current.write(feedbackId, recorded({ usefulness: "UNHELPFUL" }), {
				resolution: "DISAGREE",
				comment: "The rename was its own pull request.",
			});
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]?.body).toStrictEqual({
			usefulness: "UNHELPFUL",
			resolution: "DISAGREE",
			comment: "The rename was its own pull request.",
		});
	});

	it("builds on the response still being written, not the older recorded one", async () => {
		const answer = deferred();
		const { result, written } = renderWrite(answer.promise);

		act(() => {
			result.current.write(feedbackId, undefined, { usefulness: "HELPFUL" });
		});
		await waitFor(() => expect(result.current.pendingResponses.has(feedbackId)).toBe(true));
		act(() => {
			result.current.write(feedbackId, undefined, { resolution: "NOT_APPLICABLE" });
		});

		answer.resolve();
		await waitFor(() => expect(written).toHaveLength(2));
		expect(written[1]?.body).toStrictEqual({ usefulness: "HELPFUL", resolution: "NOT_APPLICABLE" });
	});

	it("clears the response through the endpoint's delete", async () => {
		const { result, written } = renderWrite();

		act(() => {
			result.current.clear(feedbackId);
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]).toStrictEqual({ method: "DELETE", feedbackId, body: undefined });
	});

	it("clears rather than writes a response with nothing left in it", async () => {
		const { result, written } = renderWrite();

		act(() => {
			result.current.write(feedbackId, recorded({ usefulness: "HELPFUL" }), {
				usefulness: undefined,
			});
		});

		await waitFor(() => expect(written).toHaveLength(1));
		expect(written[0]?.method).toBe("DELETE");
	});

	it("writes no disagreement without a reason", async () => {
		const { result, written } = renderWrite();

		act(() => {
			result.current.write(feedbackId, recorded({ usefulness: "HELPFUL" }), {
				resolution: "DISAGREE",
				comment: "   ",
			});
		});

		await sleep(0);
		expect(written).toStrictEqual([]);
	});

	it("marks every read that carries a response stale in this workspace only", async () => {
		const { result, written, client } = renderWrite();
		const path = { workspaceSlug };
		const elsewhere = { workspaceSlug: "other" };
		const carriers: QueryKey[] = [
			getOwnPracticePageFeedbackQueryKey({ path }),
			getOwnPracticeProfileOverviewQueryKey({ path }),
			listOwnGroupReviewsInfiniteQueryKey({ path: { ...path, groupSlug: "packaging" } }),
			listOwnReviewsInfiniteQueryKey({ path, query: { size: 25 } }),
			listOwnItemReviewsInfiniteQueryKey({ path: { ...path, itemId: "1423" } }),
			listOwnResultsQueryKey({ path, query: { itemId: "1423" } }),
			getOwnResultQueryKey({ path: { ...path, resultId: "00000000-0000-0000-0000-000000000102" } }),
			getReviewFeedbackQueryKey({ path: { ...path, feedbackId } }),
		];
		const otherWorkspace = getOwnPracticePageFeedbackQueryKey({ path: elsewhere });
		for (const key of [...carriers, otherWorkspace]) {
			client.setQueryData(key, {});
		}

		act(() => {
			result.current.write(feedbackId, undefined, { resolution: "ADDRESSED" });
		});

		await waitFor(() => expect(written).toHaveLength(1));
		await waitFor(() => {
			expect(carriers.map((key) => client.getQueryState(key)?.isInvalidated)).toStrictEqual(
				carriers.map(() => true),
			);
		});
		expect(client.getQueryState(otherWorkspace)?.isInvalidated).toBe(false);
	});
});
