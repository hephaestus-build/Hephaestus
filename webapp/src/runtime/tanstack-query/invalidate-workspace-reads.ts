import type { QueryClient } from "@tanstack/react-query";

import { isRecord } from "@/lib/is-record";
import { queryOperationId } from "@/lib/query-operation-id";

/**
 * Marks stale every cached read of `operationIds` in one workspace, whatever its query params, and
 * no other workspace's. By operation rather than by key, because a change reaches lists whose
 * filters the caller cannot know.
 */
export async function invalidateWorkspaceReads(
	queryClient: QueryClient,
	workspaceSlug: string,
	operationIds: ReadonlySet<string>,
): Promise<void> {
	await queryClient.invalidateQueries({
		predicate: ({ queryKey }) => {
			const id = queryOperationId(queryKey);
			const [key] = queryKey;
			return (
				id !== undefined &&
				operationIds.has(id) &&
				isRecord(key) &&
				isRecord(key.path) &&
				key.path.workspaceSlug === workspaceSlug
			);
		},
	});
}
