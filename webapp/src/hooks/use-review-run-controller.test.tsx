import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import type { AgentJob } from "@/api/types.gen";
import { reviewJob } from "@/components/admin/practice-reviews/story-mock-data";
import { server } from "@/mocks/server";

import { useReviewRunController } from "./use-review-run-controller";

const jobId = "11111111-1111-1111-1111-111111111111";

describe("review result-processing retry", () => {
	it("polls pending processing after execution and refreshes the settled outputs", async () => {
		const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
		const job: AgentJob = { ...reviewJob(jobId), deliveryStatus: "FAILED" };
		let outputCount = 0;
		server.use(
			http.get("*/workspaces/demo/agents/jobs/:jobId", () => HttpResponse.json(job)),
			http.post("*/workspaces/demo/agents/jobs/:jobId/delivery/retry", () => {
				job.deliveryStatus = "DELIVERED";
				outputCount = 1;
				return HttpResponse.json({ ...job, deliveryStatus: "PENDING" });
			}),
			http.get("*/workspaces/demo/practices/reviews/observations", () =>
				HttpResponse.json({ content: [], page: { totalElements: outputCount } }),
			),
			http.get("*/workspaces/demo/practices/reviews/feedback", () =>
				HttpResponse.json({ content: [], page: { totalElements: outputCount } }),
			),
		);
		const { result, unmount } = renderHook(() => useReviewRunController("demo", jobId), {
			wrapper: ({ children }: { children: ReactNode }) => (
				<QueryClientProvider client={client}>{children}</QueryClientProvider>
			),
		});
		try {
			await waitFor(() =>
				expect(result.current.feedback).toStrictEqual({ status: "ready", items: [], total: 0 }),
			);
			await waitFor(() => expect(client.isFetching()).toBe(0));
			act(() => result.current.onRetryResultProcessing());
			await waitFor(() => expect(result.current.job?.deliveryStatus).toBe("PENDING"));
			expect(result.current.feedback).toStrictEqual({ status: "pending" });
			await waitFor(() => expect(result.current.job?.deliveryStatus).toBe("DELIVERED"), {
				timeout: 7000,
			});
			await waitFor(() => {
				expect(result.current.observations).toStrictEqual({ status: "ready", items: [], total: 1 });
				expect(result.current.feedback).toStrictEqual({ status: "ready", items: [], total: 1 });
			});
		} finally {
			unmount();
			client.clear();
		}
	}, 10_000);
});
