import { useQuery } from "@tanstack/react-query";

import { adminListProductFeedbackOptions } from "@/api/@tanstack/react-query.gen";

/**
 * How many reports wait in the instance console. An instance without email tells nobody about them,
 * so this count is what leads an admin there. It reads one row: only the total matters.
 */
export function useWaitingProductFeedback(enabled: boolean): number {
	const query = useQuery({
		...adminListProductFeedbackOptions({ query: { status: "OPEN", page: 0, size: 1 } }),
		enabled,
	});
	return query.data?.totalElements ?? 0;
}
