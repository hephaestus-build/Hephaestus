import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";

import { LoginPage } from "@/components/auth/LoginPage";
import { useSignInProviders } from "@/hooks/use-sign-in-providers";
import { pageHead } from "@/lib/page-title";
import {
	accountDeletedRecently,
	clearAccountDeletedNotice,
} from "@/runtime/auth/account-deleted-notice";
import { useAuth } from "@/runtime/auth/AuthContext";
import { safeReturnTo } from "@/runtime/auth/return-to";

interface LoginSearch {
	returnTo?: string;
	error?: string;
}

export const Route = createFileRoute("/login")({
	head: pageHead("Sign in"),
	staticData: { surface: "auth" },
	validateSearch: (search): LoginSearch => ({
		returnTo: typeof search.returnTo === "string" ? search.returnTo : undefined,
		error: typeof search.error === "string" ? search.error : undefined,
	}),
	component: LoginRoute,
});

function LoginRoute() {
	const providers = useSignInProviders();
	const { error, returnTo } = Route.useSearch();
	const { login, isAuthenticated } = useAuth();
	const navigate = useNavigate();

	useEffect(() => {
		if (isAuthenticated) {
			void navigate({ href: safeReturnTo(returnTo), replace: true });
		}
	}, [isAuthenticated, navigate, returnTo]);

	// Account deletion reloads this route, so the Danger zone leaves a record behind. Read it on the
	// first render and clear it afterwards: a later reload must not repeat the notice.
	const [accountDeletionScheduled] = useState(accountDeletedRecently);
	useEffect(() => {
		clearAccountDeletedNotice();
	}, []);

	return (
		<LoginPage
			options={providers}
			error={error}
			notice={accountDeletionScheduled ? "account-deletion-scheduled" : undefined}
			onSignIn={(registrationId) => login(registrationId, returnTo)}
			devReturnTo={returnTo}
		/>
	);
}
