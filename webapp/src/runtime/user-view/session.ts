import { z } from "zod";

import environment from "@/environment";

const STORAGE_KEY = "hephaestus.user-view";

const userViewSchema = z.object({
	operatorAccountId: z.number().int().positive(),
	workspaceSlug: z.string().regex(/^[a-z0-9][a-z0-9-]{2,50}$/u),
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
		const stored = sessionStorage.getItem(STORAGE_KEY);
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
	sessionStorage.setItem(STORAGE_KEY, JSON.stringify(userViewSchema.parse(session)));
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
	sessionStorage.removeItem(STORAGE_KEY);
}

export function applyUserViewHeaders(request: Request): Request {
	const session = getUserViewSession();
	if (session === undefined) {
		return request;
	}
	const basePath = new URL(environment.serverUrl, window.location.origin).pathname.replace(
		/\/$/u,
		"",
	);
	const requestPath = new URL(request.url).pathname;
	const path =
		basePath && requestPath.startsWith(`${basePath}/`)
			? requestPath.slice(basePath.length)
			: requestPath;
	if (
		path.startsWith("/auth/") ||
		path.startsWith("/oauth/") ||
		path === "/user" ||
		path === "/user/consent" ||
		path === "/user/features"
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
