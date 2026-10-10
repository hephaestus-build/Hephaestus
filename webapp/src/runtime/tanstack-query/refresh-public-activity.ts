import type { QueryClient } from "@tanstack/react-query";

import { queryOperationId } from "@/lib/query-operation-id";

/**
 * Marks every cached public activity page stale, whatever its period and repositories, so the next
 * read asks the server. A person's own choice changes what the page lists, and the page they see
 * next must not be an earlier copy that still shows them.
 */
export async function refreshPublicActivity(queryClient: QueryClient): Promise<void> {
	await queryClient.invalidateQueries({
		predicate: ({ queryKey }) => queryOperationId(queryKey) === "getPublicActivity",
	});
}
