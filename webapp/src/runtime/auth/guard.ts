import { type QueryClient, queryOptions } from "@tanstack/react-query";

import {
	getConsentStatusOptions,
	getOwnMembershipOptions,
	getCurrentUserOptions,
} from "@/api/@tanstack/react-query.gen";
import { getCurrentUser } from "@/api/sdk.gen";
import type { CurrentUserView, WorkspaceMembership } from "@/api/types.gen";
import { isRecord } from "@/lib/is-record";
import { clearAccountDeletedNoticeUnlessFor } from "@/runtime/auth/account-deleted-notice";
import { QUERY_STALE_TIME_MS } from "@/runtime/tanstack-query/query-defaults";
import { clearUserViewUnlessOperator } from "@/runtime/user-view/session";

/** How long the identity read waits for an answer before the server counts as unreachable. */
export const IDENTITY_READ_TIMEOUT_MS = 10_000;

/** Statuses that say the server could not answer now, not that it refused the request. */
function unreachableStatus(status: number): boolean {
	return status >= 500 || status === 408 || status === 429;
}

/**
 * Whether a failed read means that the server could not answer: no response, a timeout, a server
 * error or a rate limit. Any other 4xx is the server's answer, such as a refused session or no access,
 * and asking again does not change it. Reads both a generated client's error body and an error whose
 * cause is the response.
 */
export function serverUnreachable(error: unknown): boolean {
	if (error instanceof Error && error.cause instanceof Response) {
		return unreachableStatus(error.cause.status);
	}
	if (isRecord(error) && typeof error.status === "number") {
		return unreachableStatus(error.status);
	}
	return true;
}

export function currentUserQueryOptions() {
	return queryOptions({
		...getCurrentUserOptions(),
		retry: false,
		staleTime: QUERY_STALE_TIME_MS,
		// Route guards await this shared request even when AuthProvider has no mounted observer, so
		// it ignores observer unmounts and ends only at its own deadline, which reads as unreachable.
		queryFn: async () => {
			const { data, error, response } = await getCurrentUser({
				signal: AbortSignal.timeout(IDENTITY_READ_TIMEOUT_MS),
			});
			// Every request waits for this identity, so a view left in the tab by another account, or
			// by a session that has ended, is dropped here before any request can carry it. A deletion
			// notice left by another account is dropped here too.
			if (data !== undefined && response?.ok === true) {
				clearUserViewUnlessOperator(data.id);
				clearAccountDeletedNoticeUnlessFor(String(data.id));
				return data;
			}
			if (response !== undefined && !unreachableStatus(response.status)) {
				clearUserViewUnlessOperator(undefined);
			}
			// The generated client's error body need not contain the actual HTTP status.
			throw new Error("Could not verify your session.", { cause: response ?? error });
		},
	});
}

/** Use cached identity during revalidation; cold-load outages reach the router error surface. */
export async function resolveCurrentUser(
	queryClient: QueryClient,
): Promise<CurrentUserView | null> {
	const options = currentUserQueryOptions();
	try {
		const user = await queryClient.query({ ...options, staleTime: "static" });
		void queryClient.query(options).catch(() => {
			/* the background revalidation may fail; the cached identity stands */
		});
		return user;
	} catch (error) {
		// A refused session has no identity; a server that cannot answer reaches the error surface.
		if (!serverUnreachable(error)) {
			return null;
		}
		throw error;
	}
}

export function isAppAdmin(
	user: Partial<Pick<CurrentUserView, "appRole">> | null | undefined,
): boolean {
	return user?.appRole === "APP_ADMIN";
}

export function workspaceMembershipQueryOptions(workspaceSlug: string) {
	return {
		...getOwnMembershipOptions({ path: { workspaceSlug } }),
		staleTime: QUERY_STALE_TIME_MS,
		// Retrying a refusal would stall the redirect of everyone who is legitimately not a member.
		retry: (failureCount: number, error: unknown) => serverUnreachable(error) && failureCount < 2,
	};
}

/**
 * Revalidate stale membership before navigation. A refusal is no membership, so it grants no access;
 * a server that cannot answer reaches the router error surface instead of a verdict on the reader.
 */
export async function resolveWorkspaceMembership(
	queryClient: QueryClient,
	workspaceSlug: string,
): Promise<WorkspaceMembership | null> {
	try {
		return await queryClient.query(workspaceMembershipQueryOptions(workspaceSlug));
	} catch (error) {
		if (serverUnreachable(error)) {
			throw error;
		}
		return null;
	}
}

/** Check before gated queries: the server rejects them with 428 until consent is recorded. */
export async function consentIsPending(queryClient: QueryClient): Promise<boolean> {
	if (!(await resolveCurrentUser(queryClient))) {
		return false;
	}
	try {
		const status = await queryClient.query({ ...getConsentStatusOptions({}), retry: false });
		return !status.completed;
	} catch {
		// Fail closed: `/consent` offers retry and sign-out, so holding the loader back is recoverable.
		return true;
	}
}
