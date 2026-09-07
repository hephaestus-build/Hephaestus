import { type QueryClient, queryOptions } from "@tanstack/react-query";

import {
	getConsentStatusOptions,
	getCurrentUserMembershipOptions,
	getCurrentUserOptions,
} from "@/api/@tanstack/react-query.gen";
import { getCurrentUser } from "@/api/sdk.gen";
import type { CurrentUserView, WorkspaceMembership } from "@/api/types.gen";
import { QUERY_STALE_TIME_MS } from "@/integrations/tanstack-query/query-defaults";
import { isRecord } from "@/lib/is-record";

export function currentUserQueryOptions() {
	return queryOptions({
		...getCurrentUserOptions(),
		retry: false,
		staleTime: QUERY_STALE_TIME_MS,
		queryFn: async ({ signal }) => {
			const { data, error, response } = await getCurrentUser({ signal });
			if (data !== undefined && response?.ok) return data;
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
		void queryClient.query(options).catch(() => undefined);
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
	if (!isRecord(error)) return false;
	const status = error.status;
	return typeof status === "number" && status >= 400 && status < 500;
}

export function workspaceMembershipQueryOptions(workspaceSlug: string) {
	return {
		...getCurrentUserMembershipOptions({ path: { workspaceSlug } }),
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

// Decode nested escapes before validation, with bounded work for untrusted input.
function fullyDecode(value: string): string {
	let current = value;
	for (let i = 0; i < 5; i++) {
		let decoded: string;
		try {
			decoded = decodeURIComponent(current);
		} catch {
			// Stop decoding malformed percent escapes.
			return current;
		}
		if (decoded === current) {
			return current;
		}
		current = decoded;
	}
	return current;
}

/** Accept only local absolute paths, preserving valid percent-encoded segments. */
export function safeReturnTo(value: string | undefined): string {
	if (!value) return "/";
	const decoded = fullyDecode(value);
	if (/[\s\p{Cc}]/u.test(decoded)) return "/";
	if (!decoded.startsWith("/") || decoded.startsWith("//")) return "/";
	// Browsers normalize backslashes into slashes; reject scheme and authority-like prefixes too.
	if (/^\/[\\]/.test(decoded) || /^\/+[a-z]+:/i.test(decoded) || decoded.startsWith("/@"))
		return "/";
	// Return the original so encoded query delimiters remain encoded.
	return value;
}

/** Check before gated queries: the server rejects them with 428 until consent is recorded. */
export async function consentIsPending(queryClient: QueryClient): Promise<boolean> {
	if (!(await resolveCurrentUser(queryClient))) return false;
	try {
		const status = await queryClient.query(getConsentStatusOptions({}));
		return !status.completed;
	} catch {
		// Fail closed: the consent route this branch adds offers retry and sign-out, so holding the
		// loader back is recoverable. #2047 returned false here because no such route existed yet.
		return true;
	}
}
