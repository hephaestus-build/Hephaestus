import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { server } from "@/mocks/server";
import { AuthProvider } from "@/runtime/auth/AuthContext";

import { useFeatureFlags } from "./hooks";

function wrapper(queryClient: QueryClient) {
	return function AuthWrapper({ children }: { children: ReactNode }) {
		return (
			<QueryClientProvider client={queryClient}>
				<AuthProvider>{children}</AuthProvider>
			</QueryClientProvider>
		);
	};
}

describe("useFeatureFlags", () => {
	it("stops at a refused feature read without retrying it", async () => {
		let requests = 0;
		server.use(
			http.get("*/user/features", () => {
				requests += 1;
				return HttpResponse.json({ status: 403, title: "Forbidden" }, { status: 403 });
			}),
		);

		const { result } = renderHook(() => useFeatureFlags(), {
			wrapper: wrapper(new QueryClient({ defaultOptions: { queries: { retry: false } } })),
		});

		await waitFor(() => expect(result.current.isError).toBe(true));
		expect(result.current.flags).toBeUndefined();
		expect(requests).toBe(1);
	});

	// One flag going wrong is not the flag service going down: its gate closes, the rest still answer.
	it("reads a flag the server did not send as a boolean as off, and keeps the rest of the map", async () => {
		server.use(
			http.get("*/user/features", () =>
				HttpResponse.json({
					ADMIN: "true",
					PAGE_EXPERIENCE_METRICS: true,
				}),
			),
		);

		const { result } = renderHook(() => useFeatureFlags(), {
			wrapper: wrapper(new QueryClient({ defaultOptions: { queries: { retry: false } } })),
		});

		await waitFor(() => expect(result.current.flags?.ADMIN).toBe(false));
		// The well-formed flag keeps its `true`, which a blanket coercion would flatten to `false`.
		expect(result.current.flags?.PAGE_EXPERIENCE_METRICS).toBe(true);
	});
});
