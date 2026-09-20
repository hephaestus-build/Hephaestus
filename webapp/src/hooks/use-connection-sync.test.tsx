import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { expect, it, vi } from "vitest";

import type { ConnectionSyncStatus, SyncJob } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";

import { useConnectionSync } from "./use-connection-sync";

function installSyncHandlers(requests: string[], replacement: Promise<void>) {
	server.use(
		http.get("*/workspaces/acme/connections/:connectionId/sync", ({ params }) =>
			HttpResponse.json({
				connectionId: Number(params.connectionId),
				connectionState: "ACTIVE",
				kind: "OUTLINE",
				health: "HEALTHY",
				resourceCounts: { total: 1, errored: 0, pending: 0, stale: 0 },
				backfillSupported: false,
				syncIntervalSeconds: 3600,
			} satisfies Wire<ConnectionSyncStatus>),
		),
		http.get("*/workspaces/acme/connections/:connectionId/sync/resources", () =>
			HttpResponse.json([]),
		),
		http.get(
			"*/workspaces/acme/connections/:connectionId/sync/jobs",
			async ({ params, request }) => {
				const page = new URL(request.url).searchParams.get("page");
				requests.push(`${String(params.connectionId)}:${page}`);
				if (params.connectionId === "2") {
					await replacement;
				}
				return HttpResponse.json({
					content: [
						{
							id: Number(params.connectionId) * 10 + Number(page),
							createdAt: "2026-09-01T00:00:00Z",
							status: "SUCCEEDED",
							type: "RECONCILIATION",
							trigger: "MANUAL",
							cancelRequested: false,
						} satisfies Wire<SyncJob>,
					],
					totalPages: params.connectionId === "1" ? 3 : 1,
				});
			},
		),
	);
}

it("starts replacement connections on the first page without showing the old connection's jobs", async () => {
	const replacement = deferred();
	const requests: string[] = [];
	installSyncHandlers(requests, replacement.promise);
	const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	const initialProps: { connectionId: number | undefined } = { connectionId: 1 };
	const { result, rerender, unmount } = renderHook(
		({ connectionId }: { connectionId: number | undefined }) =>
			useConnectionSync({
				workspaceSlug: "acme",
				connectionId,
				isConnectionActive: connectionId !== undefined,
				isConnectionLoading: false,
				connectionError: null,
				retryConnection: vi.fn(),
				resourceNoun: "collection",
				resourceNounPlural: "collections",
				expectedClassKeys: ["documents"],
			}),
		{
			initialProps,
			wrapper: ({ children }: { children: ReactNode }) => (
				<QueryClientProvider client={client}>{children}</QueryClientProvider>
			),
		},
	);
	try {
		await waitFor(() => expect(result.current.jobHistoryProps.jobs[0]?.id).toBe(10));
		act(() => result.current.jobHistoryProps.onPageChange(2));
		await waitFor(() => expect(result.current.jobHistoryProps.jobs[0]?.id).toBe(12));

		rerender({ connectionId: undefined });
		rerender({ connectionId: 2 });
		await waitFor(() => expect(requests).toContain("2:0"));
		expect(requests).not.toContain("2:2");
		expect(result.current.jobHistoryProps.page).toBe(0);
		expect(result.current.jobHistoryProps.jobs).toStrictEqual([]);
		expect(result.current.jobHistoryProps.isLoading).toBe(true);

		replacement.resolve();
		await waitFor(() => expect(result.current.jobHistoryProps.jobs[0]?.id).toBe(20));
	} finally {
		replacement.resolve();
		unmount();
		client.clear();
	}
});
