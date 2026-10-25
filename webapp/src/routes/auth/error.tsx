import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";

import { AuthErrorPage } from "@/components/auth/AuthErrorPage";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { pageHead } from "@/lib/page-title";
import { useAuth } from "@/runtime/auth/AuthContext";
import { safeReturnTo } from "@/runtime/auth/return-to";

interface ErrorSearch {
	code?: string;
	/** Where linking was headed, set by the server on a recent-sign-in refusal. */
	returnTo?: string;
}

export const Route = createFileRoute("/auth/error")({
	head: pageHead("Sign-in problem"),
	staticData: { surface: "auth" },
	validateSearch: (search): ErrorSearch => ({
		code: typeof search.code === "string" ? search.code : undefined,
		returnTo: typeof search.returnTo === "string" ? search.returnTo : undefined,
	}),
	component: AuthErrorRoute,
});

function AuthErrorRoute() {
	const { code, returnTo } = Route.useSearch();
	const { isAuthenticated } = useAuth();
	const recovery = code === "step_up_required";
	// Linking always names where it was headed; a refusal from before it did falls back to where linking lives.
	const destination = safeReturnTo(returnTo) === "/" ? "/settings" : safeReturnTo(returnTo);
	const [confirming, setConfirming] = useState(false);
	const confirmAccess = useConfirmAccess(confirming, destination);

	return (
		<>
			<AuthErrorPage
				code={code}
				next={
					recovery && isAuthenticated
						? { kind: "confirm-access", onConfirm: () => setConfirming(true) }
						: { kind: "sign-in", returnTo: recovery ? destination : undefined }
				}
			/>
			<ConfirmAccessDialog
				open={confirming}
				onOpenChange={setConfirming}
				providers={confirmAccess.providers}
				loading={confirmAccess.loading}
				error={confirmAccess.error}
				onRetry={confirmAccess.retry}
				onSignIn={confirmAccess.signIn}
			/>
		</>
	);
}
