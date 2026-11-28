import { onlineManager } from "@tanstack/react-query";

import { problemIsRetryable } from "@/lib/problem-detail";

export const QUERY_STALE_TIME_MS = 30_000;

/** TanStack's own retry count, which a query that sets its own `retry` function has to restate. */
export const QUERY_RETRIES = 3;

/**
 * The client's retry policy. A refusal settles at its first answer, so a cached read that is refused
 * and then retried into an outage cannot end as a recoverable failure that keeps the refused answer.
 * An imperative read (`queryClient.query`) passes `retry: false`: TanStack retries one only when the
 * client has a retry default, and a guard, loader or action decides at once what a failure means. A
 * loader's `prefetchPageRead` keeps the retries: it decides nothing, and the page shows its own failure.
 */
export function retryQuery(failureCount: number, error: unknown): boolean {
	return onlineManager.isOnline() && problemIsRetryable(error) && failureCount < QUERY_RETRIES;
}
