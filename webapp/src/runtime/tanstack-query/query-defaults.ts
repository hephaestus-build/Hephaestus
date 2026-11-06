import { onlineManager } from "@tanstack/react-query";

import { problemIsRetryable } from "@/lib/problem-detail";
import { getUserViewSession } from "@/runtime/user-view/session";

export const QUERY_STALE_TIME_MS = 30_000;

/** TanStack's own retry count, which a query that sets its own `retry` function has to restate. */
export const QUERY_RETRIES = 3;

/**
 * Whether a failed query is retried at all. A user view never retries: it starts and ends with a page
 * load, and every retried view read is checked and audited again. A query that sets its own `retry`
 * replaces the client's default outright, so it asks this too.
 */
export function sessionRetriesQueries(): boolean {
	return getUserViewSession() === undefined;
}

/**
 * The client's retry policy. A refusal settles at its first answer, so a cached read that is refused
 * and then retried into an outage cannot end as a recoverable failure that keeps the refused answer.
 * An imperative read (`queryClient.query`) passes `retry: false`: TanStack retries one only when the
 * client has a retry default, and a guard, loader or action decides at once what a failure means.
 */
export function retryQuery(failureCount: number, error: unknown): boolean {
	return (
		onlineManager.isOnline() &&
		sessionRetriesQueries() &&
		problemIsRetryable(error) &&
		failureCount < QUERY_RETRIES
	);
}
