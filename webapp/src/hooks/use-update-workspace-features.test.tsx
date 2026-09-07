import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { assert, expect, it, vi } from "vitest";

import { listWorkspacesQueryKey, updateFeaturesMutation } from "@/api/@tanstack/react-query.gen";
import type { Workspace, WorkspaceListItem } from "@/api/types.gen";

import { useUpdateWorkspaceFeatures } from "./use-update-workspace-features";

vi.mock("@/api/@tanstack/react-query.gen", async (importOriginal) => {
	const actual = await importOriginal<typeof import("@/api/@tanstack/react-query.gen")>();
	return {
		...actual,
		updateFeaturesMutation: vi.fn(),
	};
});

const workspace: WorkspaceListItem = {
	accountLogin: "acme",
	createdAt: new Date("2026-01-01"),
	displayName: "Acme",
	id: 1,
	leaderboardEnabled: true,
	leaguesEnabled: false,
	mentorEnabled: false,
	practicesEnabled: false,
	progressionEnabled: true,
	status: "ACTIVE",
	workspaceSlug: "acme",
};

function wrapper(queryClient: QueryClient) {
	return function QueryWrapper({ children }: { children: ReactNode }) {
		return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
	};
}

it("updates workspace features immediately and rolls them back on failure", async () => {
	const queryClient = new QueryClient({
		defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
	});
	const queryKey = listWorkspacesQueryKey();
	queryClient.setQueryData(queryKey, [workspace]);
	let rejectRequest: (reason: Error) => void = () => {};
	vi.mocked(updateFeaturesMutation).mockReturnValue({
		mutationFn: () =>
			new Promise<Workspace>((_resolve, reject) => {
				rejectRequest = reject;
			}),
	});
	const { result } = renderHook(
		() =>
			useUpdateWorkspaceFeatures("acme", {
				success: "Updated",
				error: "Failed",
			}),
		{ wrapper: wrapper(queryClient) },
	);

	act(() => {
		result.current.mutate({
			path: { workspaceSlug: "acme" },
			body: {
				practicesEnabled: true,
				practiceReviewAutoTriggerEnabled: false,
			},
		});
	});

	await waitFor(() => {
		const optimistic = queryClient.getQueryData<WorkspaceListItem[]>(queryKey)?.[0];
		expect(optimistic?.practicesEnabled).toBe(true);
		expect(optimistic).not.toHaveProperty("practiceReviewAutoTriggerEnabled");
	});

	rejectRequest(new Error("rejected"));

	await waitFor(() => expect(result.current.isError).toBe(true));
	const cached = queryClient.getQueryData<WorkspaceListItem[]>(queryKey);
	assert(cached);
	const [rolledBack] = cached;
	assert(rolledBack);
	expect(rolledBack.practicesEnabled).toBe(false);
});
