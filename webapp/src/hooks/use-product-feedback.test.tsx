import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it } from "vitest";

import { server } from "@/mocks/server";
import { useSubmitProductFeedback } from "./use-product-feedback";

function setup() {
	const client = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	return {
		client,
		wrapper: ({ children }: { children: ReactNode }) => (
			<QueryClientProvider client={client}>{children}</QueryClientProvider>
		),
	};
}

afterEach(cleanup);

describe("product feedback wire contract", () => {
	it("sends account feedback without a workspace or implicit page data", async () => {
		let received: unknown;
		server.use(
			http.post("*/product-feedback", async ({ request }) => {
				received = await request.json();
				return new HttpResponse(null, { status: 202 });
			}),
		);
		const { wrapper } = setup();
		const { result } = renderHook(() => useSubmitProductFeedback(undefined), { wrapper });
		await act(async () => {
			await expect(
				result.current.submit({ kind: "BUG", message: "Cannot open reviews" }),
			).resolves.toBe(true);
		});
		expect(received).toStrictEqual({ kind: "BUG", message: "Cannot open reviews" });
	});

	it("keeps a failed submission actionable and does not automatically retry a POST", async () => {
		let requests = 0;
		server.use(
			http.post("*/workspaces/acme/product-feedback", () => {
				requests += 1;
				return HttpResponse.json({ status: 429 }, { status: 429 });
			}),
		);
		const { wrapper } = setup();
		const { result } = renderHook(() => useSubmitProductFeedback("acme"), { wrapper });
		await act(async () => {
			await expect(result.current.submit({ kind: "FEEDBACK", message: "A draft" })).resolves.toBe(
				false,
			);
		});
		await waitFor(() => expect(result.current.error).toContain("Wait a minute"));
		expect(requests).toBe(1);
	});

	it("sends one request when feedback is submitted twice at once", async () => {
		let requests = 0;
		server.use(
			http.post("*/product-feedback", () => {
				requests += 1;
				return new HttpResponse(null, { status: 202 });
			}),
		);
		const { wrapper } = setup();
		const { result } = renderHook(() => useSubmitProductFeedback(undefined), { wrapper });
		await act(async () => {
			const first = result.current.submit({ kind: "FEEDBACK", message: "One submission" });
			await expect(result.current.submit({ kind: "FEEDBACK", message: "Duplicate" })).resolves.toBe(
				false,
			);
			await expect(first).resolves.toBe(true);
		});
		expect(requests).toBe(1);
	});
});
