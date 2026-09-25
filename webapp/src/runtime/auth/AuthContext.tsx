import { useQuery } from "@tanstack/react-query";
import { createContext, type ReactNode, useContext } from "react";
import { toast } from "sonner";

import { authClient, toUserProfile, type UserProfile } from "./auth-client";
import { isAppAdmin as computeIsAppAdmin, currentUserQueryOptions } from "./guard";

import { hasText } from "@/lib/text";
import {
	clearUserView,
	getUserViewSession,
	type UserViewSession,
} from "@/runtime/user-view/session";

export type { UserProfile } from "./auth-client";

export interface AuthContextType {
	isAuthenticated: boolean;
	isLoading: boolean;
	isError: boolean;
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
	isCurrentUser: (candidateLogin?: string) => boolean;
	getUserId: () => string | undefined;
	getGitProviderId: () => string | undefined;
	getUserProfilePictureUrl: () => string;
	hasGitLabIdentity: boolean;
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
		toast.error("Could not confirm sign-out. Please try again.");
	}
}

interface AuthProviderProps {
	children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
	const userQuery = useQuery(currentUserQueryOptions());

	const user = userQuery.data ?? null;
	// The identity fetch has already dropped a view that belongs to anyone else.
	const viewed = user ? getUserViewSession() : undefined;
	const isLoading = userQuery.isPending;
	const { isError } = userQuery;

	const userProfile = user ? toUserProfile(user) : undefined;

	const isAppAdmin = !viewed && computeIsAppAdmin(user);

	const hasRole = (role: string) => !viewed && (user?.roles ?? []).includes(role);

	const username = viewed?.login ?? user?.username ?? undefined;

	const isCurrentUser = (candidateLogin?: string) =>
		hasText(candidateLogin) &&
		hasText(username) &&
		username.toLowerCase() === candidateLogin.toLowerCase();
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

	const value: AuthContextType = {
		isAuthenticated: user !== null,
		isLoading,
		isError,
		username,
		userRoles: viewed ? [] : (user?.roles ?? []),
		isAppAdmin,
		userProfile,
		userView: viewed,
		login,
		linkAccount,
		logout,
		hasRole,
		isCurrentUser,
		getUserId,
		getGitProviderId,
		getUserProfilePictureUrl,
		hasGitLabIdentity: viewed ? false : (user?.hasGitLabIdentity ?? false),
		linkedProviders: viewed ? [] : (userProfile?.linkedProviders ?? []),
	};

	return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
