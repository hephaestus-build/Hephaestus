import { queryOptions } from "@tanstack/react-query";

import { getFeedbackResponseQueryKey } from "@/api/@tanstack/react-query.gen";
import { getFeedbackResponse } from "@/api/sdk.gen";

/** A 204 is a successful, unanswered response. Query caches cannot store undefined. */
export function feedbackResponseOptions(
	options: Parameters<typeof getFeedbackResponseQueryKey>[0],
) {
	return queryOptions({
		queryKey: getFeedbackResponseQueryKey(options),
		queryFn: async ({ signal }) => {
			const { data } = await getFeedbackResponse({ ...options, signal, throwOnError: true });
			return data ?? null;
		},
	});
}
