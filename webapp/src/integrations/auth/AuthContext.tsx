import { useQuery } from "@tanstack/react-query";
import { createContext, type ReactNode, useContext } from "react";
import { toast } from "sonner";

import { authClient, toUserProfile, type UserProfile } from "./auth-client";
import { isAppAdmin as computeIsAppAdmin, currentUserQueryOptions } from "./guard";

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
	linkedProviders: Array<{ type: string; serverUrl?: string }>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function useAuth() {
	const context = useContext(AuthContext);
	if (!context) {
		throw new Error("useAuth must be used within an AuthProvider");
	}
	return context;
}

interface AuthProviderProps {
	children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
	const userQuery = useQuery(currentUserQueryOptions());

	const user = userQuery.data ?? null;
	const isLoading = userQuery.isPending;
	const isError = userQuery.isError;

	const userProfile = user ? toUserProfile(user) : undefined;

	const isAppAdmin = computeIsAppAdmin(user);

	const login = (idpHint?: string, returnTo?: string) => {
		authClient.login(idpHint, returnTo);
	};

	const linkAccount = (providerAlias: string, returnTo?: string) => {
		const destination =
			returnTo ?? (typeof window !== "undefined" ? window.location.pathname : undefined);
		authClient.linkAccount(providerAlias, destination);
	};

	const logout = async () => {
		try {
			await authClient.logout();
		} catch {
			toast.error("Could not confirm sign-out. Please try again.");
		}
	};

	const hasRole = (role: string) => (user?.roles ?? []).includes(role);

	const isCurrentUser = (candidateLogin?: string) =>
		!!candidateLogin &&
		!!user?.username &&
		user.username.toLowerCase() === candidateLogin.toLowerCase();
	const getUserId = () => (user?.id != null ? String(user.id) : undefined);

	const getGitProviderId = () => user?.gitProviderId ?? undefined;

	const getUserProfilePictureUrl = () => {
		if (user?.avatarUrl) {
			return user.avatarUrl;
		}
		if (user?.identityProvider === "GITHUB" && user.gitProviderId) {
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
	};

	return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
