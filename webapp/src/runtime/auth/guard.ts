import { noop, type QueryClient, queryOptions } from "@tanstack/react-query";

import {
	getConsentStatusQueryKey,
	getCurrentUserOptions,
	getMemberOnboardingOptions,
	getOwnMembershipOptions,
	listWorkspacesOptions,
} from "@/api/@tanstack/react-query.gen";
import { getCurrentUser } from "@/api/sdk.gen";
import type { ConsentStatus, CurrentUserView, WorkspaceMembership } from "@/api/types.gen";
import { isRecord } from "@/lib/is-record";
import { clearAccountDeletedNoticeUnlessFor } from "@/runtime/auth/account-deleted-notice";
import { QUERY_STALE_TIME_MS } from "@/runtime/tanstack-query/query-defaults";
import { clearUserViewUnlessOperator, getUserViewSession } from "@/runtime/user-view/session";

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

// A gate decides on one answer; a retry would only hold the navigation back.
export const workspaceListGateOptions = () => ({ ...listWorkspacesOptions(), retry: false });
export const memberOnboardingGateOptions = (workspaceSlug: string) => ({
	...getMemberOnboardingOptions({ path: { workspaceSlug } }),
	retry: false,
});

/**
 * Whether the workspace gate sends a member who has not finished setup to it. Owners must be able to
 * repair setup, and a user view never completes it: the saved choices belong to the signed-in account.
 */
export function onboardingGateApplies(workspaceSlug: string, pathname: string): boolean {
	const base = `/w/${encodeURIComponent(workspaceSlug)}`;
	return (
		getUserViewSession() === undefined &&
		pathname !== `${base}/onboarding` &&
		!pathname.startsWith(`${base}/admin`)
	);
}

async function startWorkspaceReads(
	queryClient: QueryClient,
	workspace: { slug: string; pathname: string } | undefined,
) {
	const listed = await queryClient.query(workspaceListGateOptions());
	if (
		workspace === undefined ||
		!listed.some(({ workspaceSlug }) => workspaceSlug === workspace.slug)
	) {
		return;
	}
	await Promise.all([
		queryClient.query(workspaceMembershipQueryOptions(workspace.slug)),
		onboardingGateApplies(workspace.slug, workspace.pathname)
			? queryClient.query(memberOnboardingGateOptions(workspace.slug))
			: undefined,
	]);
}

/**
 * Starts the reads that the workspace gates under `_authenticated` await, instead of one level at a
 * time: the router runs a route's `beforeLoad` only after its parent's has finished. Call it only once
 * the reader's consent is recorded, since no protected read leaves before then. A workspace's own reads
 * wait until the list names it, because the server answers them with a 401 for some readers who are not
 * members, and a 401 starts session recovery. The gate that awaits a read reports its failure, so a
 * failure here is dropped.
 */
export function startGateReads(
	queryClient: QueryClient,
	workspace: { slug: string; pathname: string } | undefined,
) {
	void startWorkspaceReads(queryClient, workspace).catch(noop);
}

/**
 * Check before gated queries: the server rejects them with 428 until consent is recorded. The identity
 * carries the reader's consent, so this sends no read of its own. A cached identity revalidates in the
 * background, so consent that an operator makes outstanding again (new wording, another research
 * programme) is seen one navigation late; the server's 428 still refuses the reads in between.
 */
export async function consentIsPending(queryClient: QueryClient): Promise<boolean> {
	const user = await resolveCurrentUser(queryClient);
	return user !== null && !user.consent.completed;
}

/** Records a consent answer where both the consent page and the gates read it. */
export function rememberConsent(queryClient: QueryClient, status: ConsentStatus) {
	queryClient.setQueryData(getConsentStatusQueryKey({}), status);
	queryClient.setQueryData(
		currentUserQueryOptions().queryKey,
		(user) => user && { ...user, consent: status },
	);
}
