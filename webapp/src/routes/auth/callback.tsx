import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { useSpinDelay } from "spin-delay";

import { SigningInPage } from "@/components/auth/SigningInPage";
import { pageHead } from "@/lib/page-title";
import { useAuth } from "@/runtime/auth/AuthContext";
import { safeReturnTo } from "@/runtime/auth/return-to";

interface CallbackSearch {
	returnTo?: string;
}

export const Route = createFileRoute("/auth/callback")({
	head: pageHead("Signing in"),
	staticData: { surface: "auth" },
	validateSearch: (search): CallbackSearch => ({
		returnTo: typeof search.returnTo === "string" ? search.returnTo : undefined,
	}),
	component: AuthCallbackPage,
});

function AuthCallbackPage() {
	const { returnTo } = Route.useSearch();
	const { isLoading, isError } = useAuth();
	const navigate = useNavigate();

	useEffect(() => {
		if (isLoading) {
			return;
		}
		if (isError) {
			void navigate({ to: "/login", search: { returnTo: safeReturnTo(returnTo) }, replace: true });
			return;
		}
		void navigate({ href: safeReturnTo(returnTo), replace: true });
	}, [isLoading, isError, returnTo, navigate]);

	// Only a slow check offers the way back, so a fast return does not flash a competing choice.
	// `ssr: false`: the default shows the delayed state on the first render.
	const slow = useSpinDelay(true, { delay: 1000, ssr: false });

	return <SigningInPage returnTo={safeReturnTo(returnTo)} showBackLink={slow} />;
}
