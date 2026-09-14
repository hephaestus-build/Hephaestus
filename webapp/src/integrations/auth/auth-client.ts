import { logout } from "@/api/sdk.gen";
import type { CurrentUserView } from "@/api/types.gen";
import environment from "@/environment";
import { safeReturnTo } from "@/integrations/auth/guard";
import { withSessionLock } from "./session-lock";

export interface UserProfile {
	id: string;
	username: string;
	firstName: string;
	lastName: string;
	email: string;
	name: string;
	roles: string[];
	githubId?: string;
	gitlabId?: string;
	identityProvider?: string;
	linkedProviders: Array<{ type: string; serverUrl?: string }>;
}

const serverUrl = () => environment.serverUrl.replace(/\/$/, "");

function readCookie(name: string): string | undefined {
	const value = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))?.[1];
	return value === undefined ? undefined : decodeURIComponent(value);
}

export function csrfHeaders(): Record<string, string> {
	const token = readCookie(environment.xsrfCookieName);
	return token ? { "X-XSRF-TOKEN": token } : {};
}

export function applyStateChangingHeaders(request: Request): Request {
	const method = request.method.toUpperCase();
	if (method !== "GET" && method !== "HEAD" && method !== "OPTIONS") {
		for (const [key, value] of Object.entries(csrfHeaders())) {
			request.headers.set(key, value);
		}
	}
	return request;
}

export const authClient = {
	login(idpHint?: string, returnTo?: string): void {
		const provider = idpHint && idpHint.length > 0 ? idpHint : "github";
		const url = new URL(`${serverUrl()}/auth/login`);
		url.searchParams.set("provider", provider);
		url.searchParams.set("returnTo", safeReturnTo(returnTo));
		window.location.assign(url.toString());
	},

	linkAccount(providerAlias: string, returnTo?: string): void {
		const url = new URL(`${serverUrl()}/auth/login`);
		url.searchParams.set("provider", providerAlias);
		url.searchParams.set("mode", "link");
		url.searchParams.set("returnTo", safeReturnTo(returnTo));
		window.location.assign(url.toString());
	},

	async devLogin(username: string, admin: boolean, returnTo?: string): Promise<void> {
		// oxlint-disable-next-line no-restricted-globals -- The development-only endpoint has no generated SDK operation.
		const response = await fetch(`${serverUrl()}/auth/dev-login`, {
			method: "POST",
			credentials: "include",
			headers: { "Content-Type": "application/json", ...csrfHeaders() },
			body: JSON.stringify({ username, admin }),
		});
		if (!response.ok) {
			throw new Error(`Dev sign-in failed (${response.status})`);
		}
		window.location.assign(safeReturnTo(returnTo));
	},

	async logout(): Promise<void> {
		await withSessionLock(async () => {
			const { response, error } = await logout();
			if (!response?.ok && response?.status !== 401) {
				throw new Error("Could not sign out.", { cause: response ?? error });
			}
		});
		window.location.assign("/");
	},
};

export function toUserProfile(user: CurrentUserView): UserProfile {
	const name = user.displayName ?? user.username ?? "";
	const [firstName = "", ...rest] = name.split(" ");
	return {
		id: String(user.id),
		username: user.username ?? "",
		firstName,
		lastName: rest.join(" "),
		email: user.primaryEmail ?? "",
		name,
		roles: user.roles ?? [],
		githubId: user.identityProvider === "GITHUB" ? (user.gitProviderId ?? undefined) : undefined,
		gitlabId: user.identityProvider === "GITLAB" ? (user.gitProviderId ?? undefined) : undefined,
		identityProvider: user.identityProvider ?? undefined,
		linkedProviders: (user.linkedProviders ?? []).map((p) => ({
			type: p.type ?? "",
			serverUrl: p.serverUrl ?? undefined,
		})),
	};
}
