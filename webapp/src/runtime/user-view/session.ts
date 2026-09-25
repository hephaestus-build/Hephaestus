import { z } from "zod";

import { apiBasePath } from "@/runtime/api-base-path";

export const USER_VIEW_STORAGE_KEY = "hephaestus.user-view";

const userViewSchema = z.object({
	operatorAccountId: z.number().int().positive(),
	workspaceSlug: z.string().regex(/^[a-z0-9][a-z0-9-]{2,50}$/u),
	workspaceName: z.string().min(1),
	userId: z.number().int().positive(),
	login: z.string().min(1),
	name: z.string().min(1),
	hasAccount: z.boolean(),
	reason: z.string().trim().min(1).max(500),
});

export type UserViewSession = z.infer<typeof userViewSchema>;

export function getUserViewSession(): UserViewSession | undefined {
	if (typeof window === "undefined") {
		return undefined;
	}
	try {
		const stored = sessionStorage.getItem(USER_VIEW_STORAGE_KEY);
		if (stored === null) {
			return undefined;
		}
		const parsed: unknown = JSON.parse(stored);
		return userViewSchema.parse(parsed);
	} catch {
		return undefined;
	}
}

export function startUserView(session: UserViewSession): void {
	sessionStorage.setItem(USER_VIEW_STORAGE_KEY, JSON.stringify(userViewSchema.parse(session)));
	window.location.assign(`/w/${session.workspaceSlug}/user/${encodeURIComponent(session.login)}`);
}

export function exitUserView(): void {
	const workspaceSlug = getUserViewSession()?.workspaceSlug;
	clearUserView();
	if (workspaceSlug === undefined) {
		window.location.assign("/admin/workspaces");
	} else {
		window.location.assign(`/admin/workspaces/${workspaceSlug}/users`);
	}
}

export function clearUserView(): void {
	sessionStorage.removeItem(USER_VIEW_STORAGE_KEY);
}

export function clearUserViewUnlessOperator(accountId: number | undefined): void {
	if (getUserViewSession()?.operatorAccountId !== accountId) {
		clearUserView();
	}
}

export function applyUserViewHeaders(request: Request): Request {
	const session = getUserViewSession();
	if (session === undefined) {
		return request;
	}
	const basePath = apiBasePath();
	const requestPath = new URL(request.url).pathname;
	const path =
		basePath && requestPath.startsWith(`${basePath}/`)
			? requestPath.slice(basePath.length)
			: requestPath;
	// These are the administrator's own account; the server decides which reads accept the view.
	if (
		path.startsWith("/auth/") ||
		path.startsWith("/oauth/") ||
		path === "/user" ||
		path === "/user/consent" ||
		path === "/user/features" ||
		path === "/user/identities" ||
		path === "/identity-providers"
	) {
		return request;
	}
	for (const [name, value] of Object.entries(userViewHeaders(session))) {
		request.headers.set(name, value);
	}
	return request;
}

export function userViewHeaders(session = getUserViewSession()): Record<string, string> {
	if (session === undefined) {
		return {};
	}
	return {
		"X-User-View-Workspace": session.workspaceSlug,
		"X-User-View-User": String(session.userId),
		"X-User-View-Reason": encodeURIComponent(session.reason),
	};
}
