import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";

import { buttonVariants } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useAuth } from "@/integrations/auth/AuthContext";
import { safeReturnTo } from "@/integrations/auth/guard";

interface CallbackSearch {
	returnTo?: string;
}

export const Route = createFileRoute("/auth/callback")({
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
		if (isLoading) return;
		if (isError) {
			void navigate({ to: "/login", search: { returnTo: safeReturnTo(returnTo) }, replace: true });
			return;
		}
		void navigate({ href: safeReturnTo(returnTo), replace: true });
	}, [isLoading, isError, returnTo, navigate]);

	return (
		<div className="flex min-h-[100dvh] flex-col items-center justify-center gap-4">
			<Spinner className="size-8" aria-label="Signing you in" />
			<Link
				to="/login"
				search={{ returnTo: safeReturnTo(returnTo) }}
				className={buttonVariants({ variant: "outline", size: "sm" })}
			>
				Back to sign in
			</Link>
		</div>
	);
}
