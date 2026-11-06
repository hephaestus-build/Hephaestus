import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it } from "vitest";

import { server } from "@/mocks/server";

import { useWaitingProductFeedback } from "./use-waiting-product-feedback";

function wrapper({ children }: { children: ReactNode }) {
	const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

afterEach(cleanup);

describe("useWaitingProductFeedback", () => {
	it("counts the open reports, however few rows it reads", async () => {
		server.use(
			http.get("*/admin/product-feedback", () =>
				HttpResponse.json({ content: [], page: 0, size: 1, totalElements: 3, totalPages: 3 }),
			),
		);
		const { result } = renderHook(() => useWaitingProductFeedback(true), { wrapper });

		await waitFor(() => expect(result.current).toBe(3));
	});

	it("asks nothing for someone who is not an instance admin", async () => {
		let asked = false;
		server.use(
			http.get("*/admin/product-feedback", () => {
				asked = true;
				return HttpResponse.json({});
			}),
		);
		const { result } = renderHook(() => useWaitingProductFeedback(false), { wrapper });

		expect(asked).toBe(false);
		expect(result.current).toBe(0);
	});

	it("stays quiet when the count cannot load", async () => {
		let refused = false;
		server.use(
			http.get("*/admin/product-feedback", () => {
				refused = true;
				return new HttpResponse(null, { status: 403 });
			}),
		);
		const { result } = renderHook(() => useWaitingProductFeedback(true), { wrapper });

		await waitFor(() => expect(refused).toBe(true));
		expect(result.current).toBe(0);
	});
});
