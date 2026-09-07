import { useQuery } from "@tanstack/react-query";
import { createFileRoute, redirect } from "@tanstack/react-router";

import { getWorkspaceOptions } from "@/api/@tanstack/react-query.gen";
import { LoginCard } from "@/components/auth/LoginCard";
import { useSignInProviders } from "@/hooks/use-sign-in-providers";
import { useAuth } from "@/integrations/auth/AuthContext";
import { resolveCurrentUser } from "@/integrations/auth/guard";

interface WorkspaceLoginSearch {
	error?: string;
}

export const Route = createFileRoute("/w/$workspaceSlug/login")({
	staticData: { surface: "auth" },
	validateSearch: (search): WorkspaceLoginSearch => ({
		error: typeof search.error === "string" ? search.error : undefined,
	}),
	beforeLoad: async ({ context, params }) => {
		const user = await resolveCurrentUser(context.queryClient);
		if (user) {
			throw redirect({
				to: "/w/$workspaceSlug",
				params: { workspaceSlug: params.workspaceSlug },
			});
		}
	},
	component: WorkspaceLoginPage,
});

function WorkspaceLoginPage() {
	const providers = useSignInProviders();
	const { workspaceSlug } = Route.useParams();
	const { error } = Route.useSearch();
	const { login } = useAuth();

	const { data: workspace } = useQuery({
		...getWorkspaceOptions({ path: { workspaceSlug } }),
		staleTime: 5 * 60 * 1000,
		retry: false,
	});

	const heading = workspace?.displayName
		? `Sign in to ${workspace.displayName}`
		: "Sign in to your workspace";

	return (
		<LoginCard
			options={providers}
			title={heading}
			description="Sign in to continue to this workspace."
			error={error}
			onSignIn={(registrationId) => login(registrationId, `/w/${workspaceSlug}`)}
			devReturnTo={`/w/${workspaceSlug}`}
		/>
	);
}
