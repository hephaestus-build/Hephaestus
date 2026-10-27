import { useQuery } from "@tanstack/react-query";
import { createContext, type ReactNode, useContext, useState } from "react";
import { toast } from "sonner";

import { authClient, toUserProfile, type UserProfile } from "./auth-client";
import {
	isAppAdmin as computeIsAppAdmin,
	currentUserQueryOptions,
	serverUnreachable,
} from "./guard";

import { hasText } from "@/lib/text";
import {
	clearUserView,
	getUserViewSession,
	type UserViewSession,
} from "@/runtime/user-view/session";

export type { UserProfile } from "./auth-client";

export type Session = "loading" | "signed-in" | "signed-out" | "unavailable";

export interface AuthContextType {
	isAuthenticated: boolean;
	isLoading: boolean;
	isError: boolean;
	/** Who the server says is signed in. Unavailable means it could not answer; the session may still hold. */
	session: Session;
	/** Reads the identity again now, as when the reader asks to try again. */
	retrySession: () => void;
	username: string | undefined;
	userRoles: string[];
	isAppAdmin: boolean;
	userProfile: UserProfile | undefined;
	/** The read-only view this administrator opened, if one is active. */
	userView: UserViewSession | undefined;
	login: (idpHint?: string, returnTo?: string) => void;
	linkAccount: (providerAlias: string, returnTo?: string) => void;
	logout: () => Promise<void>;
	hasRole: (role: string) => boolean;
	getUserId: () => string | undefined;
	getGitProviderId: () => string | undefined;
	getUserProfilePictureUrl: () => string;
	linkedProviders: { type: string; serverUrl?: string }[];
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function useAuth() {
	const context = useContext(AuthContext);
	if (!context) {
		throw new Error("useAuth must be used within an AuthProvider");
	}
	return context;
}

function login(idpHint?: string, returnTo?: string) {
	authClient.login(idpHint, returnTo);
}

function linkAccount(providerAlias: string, returnTo?: string) {
	const destination =
		returnTo ?? (typeof window === "undefined" ? undefined : window.location.pathname);
	authClient.linkAccount(providerAlias, destination);
}

async function logout() {
	try {
		await authClient.logout();
		clearUserView();
	} catch {
		toast.error("We could not confirm that you signed out. Try again.");
	}
}

const UNAVAILABLE_RETRY_FIRST_MS = 2000;
const UNAVAILABLE_RETRY_CAP_MS = 30_000;

interface AuthProviderProps {
	children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
	const userQuery = useQuery({
		...currentUserQueryOptions(),
		// Ask again while the server cannot answer, doubling the wait up to 30 seconds.
		refetchInterval: ({ state }) =>
			state.data === undefined && state.status === "error" && serverUnreachable(state.error)
				? Math.min(
						UNAVAILABLE_RETRY_FIRST_MS * 2 ** (state.errorUpdateCount - 1),
						UNAVAILABLE_RETRY_CAP_MS,
					)
				: false,
	});
	// A new request without data clears the error, so the last answer is kept until the next one.
	// Only a page load leaves the signed-in state (sign-out reloads), so it never needs clearing.
	const [lastError, setLastError] = useState<Error | null>(null);
	if (userQuery.error !== null && userQuery.error !== lastError) {
		setLastError(userQuery.error);
	}

	const user = userQuery.data ?? null;
	// The identity fetch has already dropped a view that belongs to anyone else.
	const viewed = user ? getUserViewSession() : undefined;
	const isLoading = userQuery.isPending;
	const { isError } = userQuery;

	const userProfile = user ? toUserProfile(user) : undefined;

	const isAppAdmin = !viewed && computeIsAppAdmin(user);

	const hasRole = (role: string) => !viewed && (user?.roles ?? []).includes(role);

	// A viewed account without a workspace login must not inherit the operator's login.
	const username = viewed ? (viewed.providerLogin ?? undefined) : (user?.username ?? undefined);

	const getUserId = () => (viewed !== undefined || user?.id == null ? undefined : String(user.id));

	const getGitProviderId = () => (viewed ? undefined : (user?.gitProviderId ?? undefined));

	const getUserProfilePictureUrl = () => {
		if (hasText(user?.avatarUrl)) {
			return user.avatarUrl;
		}
		if (user?.identityProvider === "GITHUB" && hasText(user.gitProviderId)) {
			return `https://avatars.githubusercontent.com/u/${user.gitProviderId}`;
		}
		return "";
	};

	let session: Session = "signed-out";
	if (user !== null) {
		session = "signed-in";
	} else if (lastError !== null && serverUnreachable(lastError)) {
		session = "unavailable";
	} else if (isLoading) {
		session = "loading";
	}

	const value: AuthContextType = {
		isAuthenticated: user !== null,
		isLoading,
		isError,
		session,
		retrySession: () => {
			void userQuery.refetch();
		},
		username,
		userRoles: viewed ? [] : (user?.roles ?? []),
		isAppAdmin,
		userProfile,
		userView: viewed,
		login,
		linkAccount,
		logout,
		hasRole,
		getUserId,
		getGitProviderId,
		getUserProfilePictureUrl,
		linkedProviders: viewed ? [] : (userProfile?.linkedProviders ?? []),
	};

	return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
