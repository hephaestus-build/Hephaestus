import { type QueryClient, queryOptions } from "@tanstack/react-query";

import {
	getConsentStatusOptions,
	getOwnMembershipOptions,
	getCurrentUserOptions,
} from "@/api/@tanstack/react-query.gen";
import { getCurrentUser } from "@/api/sdk.gen";
import type { CurrentUserView, WorkspaceMembership } from "@/api/types.gen";
import { isRecord } from "@/lib/is-record";
import { QUERY_STALE_TIME_MS } from "@/runtime/tanstack-query/query-defaults";
import { clearUserViewUnlessOperator } from "@/runtime/user-view/session";

export function currentUserQueryOptions() {
	return queryOptions({
		...getCurrentUserOptions(),
		retry: false,
		staleTime: QUERY_STALE_TIME_MS,
		// Route guards await this shared request even when AuthProvider has no mounted observer, so
		// its lifetime is deliberately independent of observer unmounts — hence no `signal`.
		queryFn: async () => {
			const { data, error, response } = await getCurrentUser();
			// Every request waits for this identity, so a view left in the tab by another account, or
			// by a session that has ended, is dropped here before any request can carry it.
			if (data !== undefined && response?.ok === true) {
				clearUserViewUnlessOperator(data.id);
				return data;
			}
			if (response?.status === 401) {
				clearUserViewUnlessOperator(undefined);
			}
			// The generated client's error body need not contain the actual HTTP status.
			throw new Error("Could not verify your session.", { cause: response ?? error });
		},
	});
}

/**
 * Whether a failed identity read means that the server could not answer. Only a 401 or 403 is an
 * answer about the session, and signing in, as another account if need be, is its remedy. A server
 * error or a lost connection signs no one out.
 */
export function sessionUnavailable(error: unknown): boolean {
	return !(
		error instanceof Error &&
		error.cause instanceof Response &&
		(error.cause.status === 401 || error.cause.status === 403)
	);
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
		if (error instanceof Error && error.cause instanceof Response && error.cause.status === 401) {
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

function isServerRefusal(error: unknown): boolean {
	if (!isRecord(error)) {
		return false;
	}
	const { status } = error;
	return typeof status === "number" && status >= 400 && status < 500;
}

export function workspaceMembershipQueryOptions(workspaceSlug: string) {
	return {
		...getOwnMembershipOptions({ path: { workspaceSlug } }),
		staleTime: QUERY_STALE_TIME_MS,
		// Retrying a refusal would stall the redirect of everyone who is legitimately not a member.
		retry: (failureCount: number, error: unknown) => !isServerRefusal(error) && failureCount < 2,
	};
}

/** Revalidate stale membership before navigation; unverifiable membership grants no access. */
export async function resolveWorkspaceMembership(
	queryClient: QueryClient,
	workspaceSlug: string,
): Promise<WorkspaceMembership | null> {
	try {
		return await queryClient.query(workspaceMembershipQueryOptions(workspaceSlug));
	} catch {
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
