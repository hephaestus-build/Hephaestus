import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { toast } from "sonner";

import { LoginCard } from "@/components/auth/LoginCard";
import { useSignInProviders } from "@/hooks/use-sign-in-providers";
import { ACCOUNT_DELETED_NOTICE_KEY } from "@/integrations/auth/account-deleted-notice";
import { useAuth } from "@/integrations/auth/AuthContext";
import { safeReturnTo } from "@/integrations/auth/guard";

interface LoginSearch {
	returnTo?: string;
	error?: string;
}

export const Route = createFileRoute("/login")({
	staticData: { surface: "auth" },
	validateSearch: (search): LoginSearch => ({
		returnTo: typeof search.returnTo === "string" ? search.returnTo : undefined,
		error: typeof search.error === "string" ? search.error : undefined,
	}),
	component: LoginPage,
});

function LoginPage() {
	const providers = useSignInProviders();
	const { error, returnTo } = Route.useSearch();
	const { login, isAuthenticated } = useAuth();
	const navigate = useNavigate();

	useEffect(() => {
		if (isAuthenticated) void navigate({ href: safeReturnTo(returnTo), replace: true });
	}, [isAuthenticated, navigate, returnTo]);

	// Account deletion reloads this route; the confirmation must survive that reload.
	useEffect(() => {
		try {
			if (sessionStorage.getItem(ACCOUNT_DELETED_NOTICE_KEY) === "1") {
				sessionStorage.removeItem(ACCOUNT_DELETED_NOTICE_KEY);
				toast.success(
					"Your account is scheduled for deletion and you've been signed out everywhere. Permanent removal completes after about 48 hours.",
				);
			}
		} catch {
			// Storage can be unavailable; confirmation must not block sign-in.
		}
	}, []);

	return (
		<LoginCard
			options={providers}
			title="Welcome to Hephaestus"
			description="Your AI mentor for growing as a software engineer."
			error={error}
			onSignIn={(registrationId) => login(registrationId, returnTo)}
			devReturnTo={returnTo}
		/>
	);
}
