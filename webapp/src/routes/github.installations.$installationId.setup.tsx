import { useMutation, useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";

import {
	claimGitHubInstallationMutation,
	getProvidersOptions,
} from "@/api/@tanstack/react-query.gen";
import {
	GitHubInstallationSetupPage,
	type GitHubInstallationSetupState,
} from "@/components/create-workspace/GitHubInstallationSetupPage";
import { pageTitle } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { useAuth } from "@/runtime/auth/AuthContext";

export const Route = createFileRoute("/github/installations/$installationId/setup")({
	staticData: { surface: "auth" },
	head: () => ({
		meta: [
			{ title: pageTitle("GitHub App setup") },
			{ name: "robots", content: "noindex, nofollow" },
		],
	}),
	validateSearch: (search): { code?: string } => ({
		code: typeof search.code === "string" && search.code.length > 0 ? search.code : undefined,
	}),
	component: GitHubInstallationSetupRoute,
});

function GitHubInstallationSetupRoute() {
	const { installationId } = Route.useParams();
	const { code } = Route.useSearch();
	const { isAuthenticated, isLoading, isAppAdmin, login } = useAuth();
	const providers = useQuery({ ...getProvidersOptions(), enabled: !isAuthenticated && !isLoading });
	const navigate = useNavigate();
	const claim = useMutation({
		...claimGitHubInstallationMutation(),
		onSuccess: (result) => {
			if (result.outcome === "READY" && result.workspaceSlug !== undefined) {
				void navigate({
					to: "/w/$workspaceSlug",
					params: { workspaceSlug: result.workspaceSlug },
					replace: true,
				});
			}
		},
	});

	const id = Number(installationId);
	let state: GitHubInstallationSetupState;
	if (!Number.isSafeInteger(id) || id <= 0) {
		state = { status: "invalid" };
	} else if (isLoading || (!isAuthenticated && providers.isPending)) {
		state = { status: "pending" };
	} else if (!isAuthenticated) {
		// Signed out, the page sends nothing: the installation webhook has already told Hephaestus.
		state = {
			status: "signed-out",
			requiresApproval: providers.data?.creationPolicy !== "SELF_SERVICE",
			onSignIn: () => login(),
		};
	} else if (code === undefined) {
		state = { status: "invalid" };
	} else if (claim.data?.outcome === "AWAITING_APPROVAL") {
		state = { status: "awaiting-approval", canApprove: isAppAdmin };
	} else if (claim.data?.outcome === "SETTING_UP") {
		state = { status: "setting-up" };
	} else {
		const onConfirm = () => claim.mutate({ path: { installationId: id }, body: { code } });
		if (claim.isPending || claim.isSuccess) {
			state = { status: "pending" };
		} else if (claim.isError) {
			state = {
				status: "error",
				message: problemDetailOf(claim.error, "GitHub did not confirm the installation."),
			};
		} else {
			state = { status: "confirm", onConfirm };
		}
	}

	return <GitHubInstallationSetupPage state={state} />;
}
