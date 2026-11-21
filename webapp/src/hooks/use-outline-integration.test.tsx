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
		name: "the newer connection was created last",
		connections: [
			{ ...uninstalled, id: 1, createdAt: "2026-10-01T00:00:00Z" },
			{ ...uninstalled, id: 2, createdAt: "2026-10-20T00:00:00Z" },
		],
		repairConnectionId: 2,
	},
	{
		name: "the list names the newer connection first",
		connections: [
			{ ...uninstalled, id: 4, createdAt: "2026-10-20T00:00:00Z" },
			{ ...uninstalled, id: 3, createdAt: "2026-10-01T00:00:00Z" },
		],
		repairConnectionId: 4,
	},
] satisfies { name: string; connections: Wire<ConnectionSummary>[]; repairConnectionId: number }[])(
	"lists the provider removals of the newest disconnected connection when $name",
	async ({ connections, repairConnectionId }) => {
		server.use(
			http.get("*/workspaces/acme/connections", () => HttpResponse.json(connections)),
			http.get("*/workspaces/acme/connections/catalog", () =>
				HttpResponse.json([
					{ kind: "OUTLINE", displayName: "Outline", connected: false, connectable: true },
				]),
			),
		);
		const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
		const { result, unmount } = renderHook(() => useOutlineIntegration("acme"), {
			wrapper: ({ children }: { children: ReactNode }) => (
				<QueryClientProvider client={client}>{children}</QueryClientProvider>
			),
		});
		try {
			await waitFor(() => expect(result.current.isLoading).toBe(false));
			expect(result.current.hasConnection).toBe(false);
			expect(result.current.repairConnectionId).toBe(repairConnectionId);
		} finally {
			unmount();
			client.clear();
		}
	},
);
