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
