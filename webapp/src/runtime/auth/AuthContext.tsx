import { useQuery } from "@tanstack/react-query";
import { createContext, type ReactNode, useContext } from "react";
import { toast } from "sonner";

import { authClient, toUserProfile, type UserProfile } from "./auth-client";
import { isAppAdmin as computeIsAppAdmin, currentUserQueryOptions } from "./guard";

import { hasText } from "@/lib/text";

export type { UserProfile } from "./auth-client";

export interface AuthContextType {
	isAuthenticated: boolean;
	isLoading: boolean;
	isError: boolean;
	username: string | undefined;
	userRoles: string[];
	isAppAdmin: boolean;
	userProfile: UserProfile | undefined;
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
	isImpersonating: boolean;
	impersonatedDisplayName: string | undefined;
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
	const isLoading = userQuery.isPending;
	const { isError } = userQuery;

	const userProfile = user ? toUserProfile(user) : undefined;

	const isAppAdmin = computeIsAppAdmin(user);

	const hasRole = (role: string) => (user?.roles ?? []).includes(role);

	const isCurrentUser = (candidateLogin?: string) =>
		hasText(candidateLogin) &&
		hasText(user?.username) &&
		user.username.toLowerCase() === candidateLogin.toLowerCase();
	const getUserId = () => (user?.id == null ? undefined : String(user.id));

	const getGitProviderId = () => user?.gitProviderId ?? undefined;

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
		username: user?.username ?? undefined,
		userRoles: user?.roles ?? [],
		isAppAdmin,
		userProfile,
		login,
		linkAccount,
		logout,
		hasRole,
		isCurrentUser,
		getUserId,
		getGitProviderId,
		getUserProfilePictureUrl,
		hasGitLabIdentity: user?.hasGitLabIdentity ?? false,
		linkedProviders: userProfile?.linkedProviders ?? [],
		isImpersonating: user?.impersonating ?? false,
		impersonatedDisplayName: user?.displayName ?? undefined,
	};

	return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
