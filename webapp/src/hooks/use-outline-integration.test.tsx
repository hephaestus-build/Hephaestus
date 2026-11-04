import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { expect, it } from "vitest";

import type { ConnectionSummary } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";

import { useOutlineIntegration } from "./use-outline-integration";

const uninstalled = {
	kind: "OUTLINE",
	family: "DOCUMENTATION",
	state: "UNINSTALLED",
	displayName: "Outline",
	updatedAt: "2026-11-02T00:00:00Z",
} satisfies Wire<ConnectionSummary>;

it.each([
	{
		name: "the newest uninstalled connection could not confirm its teardown",
		connections: [
			{ ...uninstalled, id: 1, createdAt: "2026-10-01T00:00:00Z" },
			{
				...uninstalled,
				id: 2,
				createdAt: "2026-10-20T00:00:00Z",
				attentionProblem: "PROVIDER_CLEANUP_UNCONFIRMED",
			},
		],
		unconfirmed: true,
	},
	{
		name: "a newer uninstalled connection finished its teardown",
		connections: [
			{
				...uninstalled,
				id: 3,
				createdAt: "2026-10-01T00:00:00Z",
				attentionProblem: "PROVIDER_CLEANUP_UNCONFIRMED",
			},
			{ ...uninstalled, id: 4, createdAt: "2026-10-20T00:00:00Z" },
		],
		unconfirmed: false,
	},
] satisfies { name: string; connections: Wire<ConnectionSummary>[]; unconfirmed: boolean }[])(
	"reports an unconfirmed provider cleanup after a disconnect when $name",
	async ({ connections, unconfirmed }) => {
		server.use(http.get("*/workspaces/acme/connections", () => HttpResponse.json(connections)));
		const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
		const { result, unmount } = renderHook(() => useOutlineIntegration("acme"), {
			wrapper: ({ children }: { children: ReactNode }) => (
				<QueryClientProvider client={client}>{children}</QueryClientProvider>
			),
		});
		try {
			await waitFor(() => expect(result.current.isLoading).toBe(false));
			expect(result.current.hasConnection).toBe(false);
			expect(result.current.providerCleanupUnconfirmed).toBe(unconfirmed);
		} finally {
			unmount();
			client.clear();
		}
	},
);
