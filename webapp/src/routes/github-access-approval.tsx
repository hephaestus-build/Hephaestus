import { useMutation } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { z } from "zod";

import {
	authorizeGitHubAccessHandoffMutation,
	previewGitHubAccessHandoffMutation,
} from "@/api/@tanstack/react-query.gen";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { useNow } from "@/components/common/use-now";
import {
	GithubAccessApprovalPage,
	type GithubApprovalState,
} from "@/components/github-access/GithubAccessApprovalPage";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { useAuth } from "@/integrations/auth/AuthContext";
import { stepUpChallengeOf, type StepUpChallenge } from "@/lib/problem-detail";

// This route deliberately stays outside the authenticated subtree: its login redirect must never
// copy a capability fragment into a returnTo query parameter (and therefore proxy logs).
export const Route = createFileRoute("/github-access-approval")({
	staticData: { surface: "auth" },
	component: GithubApprovalContainer,
});
const storageKey = "hephaestus.github-access-approval";
const capability = z.string().regex(/^[A-Za-z0-9_-]{43}$/);
const savedApproval = z.object({ token: capability, expiresAt: z.number() });

function readCapability(now: number) {
	const fragment = capability.safeParse(window.location.hash.slice(1));
	if (fragment.success) return fragment.data;
	try {
		const raw = sessionStorage.getItem(storageKey);
		if (!raw) return undefined;
		const stored = savedApproval.safeParse(JSON.parse(raw));
		return stored.success && stored.data.expiresAt > now ? stored.data.token : undefined;
	} catch {
		return undefined;
	}
}

function GithubApprovalContainer() {
	const auth = useAuth();
	const navigate = useNavigate();
	const now = useNow();
	const [token] = useState(() => readCapability(now));
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmation = useConfirmAccess(challenge !== undefined);
	useEffect(() => {
		// Fragments do not reach the server, but removing them also keeps later navigation and
		// ordinary copy-address actions from accidentally propagating the one-use capability.
		window.history.replaceState(window.history.state, "", window.location.pathname);
	}, []);
	const preview = useMutation({ ...previewGitHubAccessHandoffMutation(), gcTime: 0 });
	const authorize = useMutation({
		...authorizeGitHubAccessHandoffMutation(),
		gcTime: 0,
		onSuccess: () => {
			try {
				sessionStorage.removeItem(storageKey);
			} catch {
				/* Storage is optional; the server consumed the capability. */
			}
		},
		onError: (error) => setChallenge(stepUpChallengeOf(error)),
	});
	const preserve = () => {
		if (!token) return;
		try {
			sessionStorage.setItem(storageKey, JSON.stringify({ token, expiresAt: now + 600_000 }));
		} catch {
			/* The recipient can reopen the original link if tab storage is unavailable. */
		}
	};
	const review = () => {
		if (token) preview.mutate({ body: { token } });
	};
	const state: GithubApprovalState = auth.isLoading
		? { status: "loading" }
		: !token
			? { status: "invalid" }
			: !auth.isAuthenticated
				? {
						status: "signed-out",
						onSignIn: () => {
							preserve();
							void navigate({ to: "/login", search: { returnTo: "/github-access-approval" } });
						},
					}
				: authorize.isSuccess
					? { status: "complete" }
					: preview.isError
						? {
								status: "error",
								error: preview.error,
								onRetry: review,
								onLinkAccount: () => {
									preserve();
									void navigate({ to: "/settings" });
								},
							}
						: {
								status: "review",
								preview: preview.data,
								onPreview: review,
								onApprove: () => authorize.mutate({ body: { token } }),
								error: authorize.error ?? undefined,
							};
	return (
		<>
			<GithubAccessApprovalPage state={state} busy={preview.isPending || authorize.isPending} />
			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) setChallenge(undefined);
				}}
				providers={confirmation.providers}
				loading={confirmation.loading}
				error={confirmation.error}
				maxAgeSeconds={challenge?.maxAgeSeconds}
				onRetry={confirmation.retry}
				onSignIn={(registrationId) => {
					preserve();
					confirmation.signIn(registrationId);
				}}
			/>
		</>
	);
}
