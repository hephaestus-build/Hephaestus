import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { assert, beforeEach, describe, expect, it, vi } from "vitest";

import {
	deleteGroupMutation,
	deletePracticeMutation,
	getPracticeQueryKey,
	listAdoptablePracticesQueryKey,
	listGroupsQueryKey,
	listPracticesQueryKey,
	placePracticeMutation,
	previewGroupAdoptionQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type * as ReactQueryGen from "@/api/@tanstack/react-query.gen";
import type { Practice } from "@/api/types.gen";
import { mockGroups, mockPractice, mockPractices } from "@/components/admin/practices/fixtures";
import { deferred } from "@/test/async";

import { usePracticeCatalogMutations } from "./use-practice-catalog-mutations";

vi.mock("@/api/@tanstack/react-query.gen", async (importOriginal) => {
	const actual = await importOriginal<typeof ReactQueryGen>();
	return {
		...actual,
		deleteGroupMutation: vi.fn(),
		deletePracticeMutation: vi.fn(),
		placePracticeMutation: vi.fn(),
	};
});

const WORKSPACE = "acme";
const queryKey = listPracticesQueryKey({ path: { workspaceSlug: WORKSPACE } });
const groupsQueryKey = listGroupsQueryKey({ path: { workspaceSlug: WORKSPACE } });
const adoptionCatalogQueryKey = listAdoptablePracticesQueryKey({
	path: { workspaceSlug: WORKSPACE },
});

/** A second optimistic write landing on one row while a placement is still in flight. */
function deactivating(slug: string) {
	return (current: Practice[] = []) =>
		current.map((item) => (item.slug === slug ? { ...item, active: false } : item));
}

function practice(slug: string, groupSlug: string | undefined, displayOrder: number): Practice {
	return {
		...mockPractice,
		id: displayOrder + 1,
		name: slug,
		slug,
		groupSlug,
		displayOrder,
	};
}

function wrapper(client: QueryClient) {
	return function QueryWrapper({ children }: { children: ReactNode }) {
		return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
	};
}

function queryClient() {
	return new QueryClient({
		defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
	});
}

describe("usePracticeCatalogMutations", () => {
	beforeEach(() => {
		vi.clearAllMocks();
	});

	it("optimistically places a practice in another bucket and updates its detail cache", async () => {
		const client = queryClient();
		const first = practice("first", "quality", 0);
		const moving = practice("moving", "quality", 1);
		const last = practice("last", "quality", 2);
		const destination = practice("destination", "delivery", 0);
		client.setQueryData(queryKey, [first, moving, last, destination]);
		client.setQueryData(
			getPracticeQueryKey({
				path: { workspaceSlug: WORKSPACE, practiceSlug: "moving" },
			}),
			moving,
		);
		const request = deferred<Practice[]>();
		vi.mocked(placePracticeMutation).mockReturnValue({
			mutationFn: async () => request.promise,
		});
		const { result } = renderHook(() => usePracticeCatalogMutations(WORKSPACE), {
			wrapper: wrapper(client),
		});

		act(() => {
			result.current.placePractice.mutate({
				path: { workspaceSlug: WORKSPACE, practiceSlug: "moving" },
				body: { groupSlug: "delivery", position: 0 },
			});
		});

		await waitFor(() =>
			expect(
				client.getQueryData<Practice[]>(queryKey)?.find(({ slug }) => slug === "moving"),
			).toMatchObject({ groupSlug: "delivery", displayOrder: 0 }),
		);
		expect(
			client.getQueryData<Practice>(
				getPracticeQueryKey({
					path: { workspaceSlug: WORKSPACE, practiceSlug: "moving" },
				}),
			),
		).toMatchObject({ groupSlug: "delivery", displayOrder: 0 });
		expect(
			client
				.getQueryData<Practice[]>(queryKey)
				?.filter(({ groupSlug }) => groupSlug === "quality")
				.map(({ displayOrder }) => displayOrder),
		).toStrictEqual([0, 1]);

		request.resolve([
			first,
			{ ...last, displayOrder: 1 },
			{ ...moving, groupSlug: "delivery", displayOrder: 0 },
			{ ...destination, displayOrder: 1 },
		]);
		await waitFor(() => expect(result.current.placePractice.isSuccess).toBe(true));
	});

	it("rolls back placement fields without overwriting another optimistic field", async () => {
		const client = queryClient();
		const practices = [practice("moving", "quality", 0), practice("remaining", "quality", 1)];
		client.setQueryData(queryKey, practices);
		const request = deferred<Practice[]>();
		vi.mocked(placePracticeMutation).mockReturnValue({
			mutationFn: async () => request.promise,
		});
		const { result } = renderHook(() => usePracticeCatalogMutations(WORKSPACE), {
			wrapper: wrapper(client),
		});

		act(() => {
			result.current.placePractice.mutate({
				path: { workspaceSlug: WORKSPACE, practiceSlug: "moving" },
				body: { position: 0 },
			});
		});
		await waitFor(() => {
			const cached = client.getQueryData<Practice[]>(queryKey);
			assert(cached);
			const [moved] = cached;
			assert(moved);
			expect(moved.groupSlug).toBeUndefined();
		});
		client.setQueryData<Practice[]>(queryKey, deactivating("moving"));
		request.reject(new Error("rejected"));

		await waitFor(() => expect(result.current.placePractice.isError).toBe(true));
		expect(
			client.getQueryData<Practice[]>(queryKey)?.find(({ slug }) => slug === "moving"),
		).toMatchObject({
			active: false,
			groupSlug: "quality",
			displayOrder: 0,
		});
	});

	it("blocks structural changes while a placement settles", async () => {
		const client = queryClient();
		client.setQueryData(queryKey, [
			practice("first", "quality", 0),
			practice("second", "quality", 1),
		]);
		const first = deferred<Practice[]>();
		const mutation = vi.fn(async () => first.promise);
		vi.mocked(placePracticeMutation).mockReturnValue({ mutationFn: mutation });
		const { result } = renderHook(() => usePracticeCatalogMutations(WORKSPACE), {
			wrapper: wrapper(client),
		});

		act(() => {
			result.current.placePractice.mutate({
				path: { workspaceSlug: WORKSPACE, practiceSlug: "first" },
				body: { groupSlug: "quality", position: 1 },
			});
		});

		await waitFor(() => expect(result.current.placePractice.isPending).toBe(true));
		expect(result.current.groupStructurePending).toBe(true);
		expect(result.current.blockedPracticeOrderBuckets).toStrictEqual(
			new Set(["__unassigned__", "quality"]),
		);
		expect(result.current.blockedMoveDestinationSlugs).toStrictEqual(
			new Set(["__unassigned__", "quality"]),
		);
		first.resolve([practice("second", "quality", 0), practice("first", "quality", 1)]);
		await waitFor(() => expect(result.current.placePractice.isSuccess).toBe(true));
	});

	it("blocks reordering only in the bucket being deleted from", async () => {
		const client = queryClient();
		client.setQueryData(queryKey, [
			practice("deleting", "quality", 0),
			practice("elsewhere", "delivery", 0),
		]);
		client.setQueryData(adoptionCatalogQueryKey, []);
		const deletion = deferred();
		vi.mocked(deletePracticeMutation).mockReturnValue({
			mutationFn: async () => deletion.promise,
		});
		const { result } = renderHook(() => usePracticeCatalogMutations(WORKSPACE), {
			wrapper: wrapper(client),
		});

		act(() => {
			result.current.deletePractice.mutate({
				path: { workspaceSlug: WORKSPACE, practiceSlug: "deleting" },
			});
		});

		await waitFor(() => expect(result.current.deletePractice.isPending).toBe(true));
		expect(result.current.blockedPracticeOrderBuckets).toStrictEqual(new Set(["quality"]));
		expect(result.current.blockedMoveDestinationSlugs).toStrictEqual(new Set(["quality"]));
		deletion.resolve();
		await waitFor(() => expect(result.current.deletePractice.isSuccess).toBe(true));
		expect(client.getQueryState(adoptionCatalogQueryKey)?.isInvalidated).toBe(true);
	});

	it("removes a deleting group from move destinations", async () => {
		const client = queryClient();
		client.setQueryData(groupsQueryKey, mockGroups);
		client.setQueryData(queryKey, mockPractices);
		const groupPreviewKey = previewGroupAdoptionQueryKey({
			path: { workspaceSlug: WORKSPACE, slug: "quality" },
		});
		client.setQueryData(groupPreviewKey, {});
		const deletion = deferred();
		vi.mocked(deleteGroupMutation).mockReturnValue({
			mutationFn: async () => deletion.promise,
		});
		const { result } = renderHook(() => usePracticeCatalogMutations(WORKSPACE), {
			wrapper: wrapper(client),
		});

		act(() => {
			result.current.deleteGroup.mutate({
				path: { workspaceSlug: WORKSPACE, groupSlug: "quality" },
			});
		});

		await waitFor(() => expect(result.current.deleteGroup.isPending).toBe(true));
		expect(result.current.blockedMoveDestinationSlugs).toStrictEqual(
			new Set(["quality", "__unassigned__"]),
		);
		deletion.resolve();
		await waitFor(() => expect(result.current.deleteGroup.isSuccess).toBe(true));
		expect(client.getQueryState(groupPreviewKey)?.isInvalidated).toBe(true);
	});

	it("removes a group's practices and refreshes the library when deletion includes practices", async () => {
		const client = queryClient();
		client.setQueryData(groupsQueryKey, mockGroups);
		client.setQueryData(queryKey, [
			practice("deleted-with-group", "quality", 0),
			practice("kept", "delivery", 0),
		]);
		client.setQueryData(adoptionCatalogQueryKey, []);
		vi.mocked(deleteGroupMutation).mockReturnValue({
			mutationFn: vi.fn().mockResolvedValue(undefined),
		});
		const { result } = renderHook(() => usePracticeCatalogMutations(WORKSPACE), {
			wrapper: wrapper(client),
		});

		act(() => {
			result.current.deleteGroup.mutate({
				path: { workspaceSlug: WORKSPACE, groupSlug: "quality" },
				query: { deletePractices: true },
			});
		});

		await waitFor(() => expect(result.current.deleteGroup.isSuccess).toBe(true));
		expect(client.getQueryData<Practice[]>(queryKey)?.map(({ slug }) => slug)).toStrictEqual([
			"kept",
		]);
		expect(client.getQueryState(adoptionCatalogQueryKey)?.isInvalidated).toBe(true);
	});
});
