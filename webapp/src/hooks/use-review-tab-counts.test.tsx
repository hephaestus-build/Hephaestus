import { focusManager, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";

import { useReviewTabCounts } from "./use-review-tab-counts";

const MINUTE = 60_000;
const initial = { reviews: 10, results: 20, problems: 25, feedback: 30 };

const paged = (total: number) => ({ content: [], totalElements: total });

function countReads(status = 200) {
	let totals = initial;
	const reads: URL[] = [];
	const endpoints = [
		["reviews", "", paged],
		["results", "/results", paged],
		["problems", "/problems", paged],
		["feedback", "/feedback", paged],
	] as const;
	server.use(
		...endpoints.map(([kind, suffix, body]) =>
			http.get(`*/workspaces/:workspaceSlug/practices/reviews${suffix}`, ({ request }) => {
				reads.push(new URL(request.url));
				return HttpResponse.json(body(totals[kind]), { status });
			}),
		),
	);
	return {
		reads,
		change: () => {
			totals = { reviews: 11, results: 22, problems: 27, feedback: 33 };
		},
	};
}

function mountCounts() {
	const client = new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false } } });
	const hook = renderHook(
		() => useReviewTabCounts("demo", { practiceSlug: ["testing"], from: "2026-09-01" }),
		{
			wrapper: ({ children }: { children: ReactNode }) => (
				<QueryClientProvider client={client}>{children}</QueryClientProvider>
			),
		},
	);
	return { ...hook, client };
}

beforeEach(() => {
	vi.useFakeTimers({ shouldAdvanceTime: true });
	focusManager.setFocused(true);
});

afterEach(() => {
	cleanup();
	focusManager.setFocused(undefined);
	vi.useRealTimers();
});

describe("practice review tab totals", () => {
	it("refreshes successful totals while mounted and preserves each endpoint's shared scope", async () => {
		const wire = countReads();
		const { result, client } = mountCounts();
		try {
			await waitFor(() => expect(result.current).toStrictEqual(initial));
			wire.change();
			await act(async () => {
				await vi.advanceTimersByTimeAsync(MINUTE);
			});
			await waitFor(() =>
				expect(result.current).toStrictEqual({
					reviews: 11,
					results: 22,
					problems: 27,
					feedback: 33,
				}),
			);
			expect(wire.reads).toHaveLength(8);
			for (const url of wire.reads) {
				expect(url.pathname).toContain("/workspaces/demo/practices/reviews");
				expect(url.searchParams.get("size")).toBe("1");
				expect(url.searchParams.get("practiceSlug")).toBe("testing");
			}
			// A staff count names no person (ADR 0125).
			expect(wire.reads.map((url) => url.searchParams.get("personId"))).toStrictEqual(
				Array.from({ length: 8 }, () => null),
			);
		} finally {
			cleanup();
			client.clear();
		}
	});

	it("keeps existing totals without polling a hidden page and refreshes once visible", async () => {
		const wire = countReads();
		const { result, client } = mountCounts();
		try {
			await waitFor(() => expect(result.current).toStrictEqual(initial));
			wire.change();
			focusManager.setFocused(false);
			await act(async () => {
				await vi.advanceTimersByTimeAsync(MINUTE);
			});
			expect(wire.reads).toHaveLength(4);
			expect(result.current).toStrictEqual(initial);
			focusManager.setFocused(true);
			await act(async () => {
				await vi.advanceTimersByTimeAsync(MINUTE);
			});
			await waitFor(() => expect(result.current.feedback).toBe(33));
		} finally {
			cleanup();
			client.clear();
		}
	});
});
