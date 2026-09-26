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
	// One flag going wrong is not the flag service going down: its gate closes, the rest still answer.
	it("reads a flag the server did not send as a boolean as off, and keeps the rest of the map", async () => {
		server.use(
			http.get("*/user/features", () =>
				HttpResponse.json({
					ADMIN: "true",
					NOTIFICATION_ACCESS: true,
					GITLAB_WORKSPACE_CREATION: false,
				}),
			),
		);

		const { result } = renderHook(() => useFeatureFlags(), {
			wrapper: wrapper(new QueryClient({ defaultOptions: { queries: { retry: false } } })),
		});

		await waitFor(() => expect(result.current.flags?.ADMIN).toBe(false));
		// Both well-formed flags: a `true` that has to survive, and the `false` the malformed one
		// collapses to, which a blanket coercion would flatten together.
		expect(result.current.flags?.NOTIFICATION_ACCESS).toBe(true);
		expect(result.current.flags?.GITLAB_WORKSPACE_CREATION).toBe(false);
	});
});
