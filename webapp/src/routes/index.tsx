import { createFileRoute, redirect } from "@tanstack/react-router";

import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import { StandardPageSurface } from "@/components/core/StandardPageSurface";
import { LandingPage } from "@/components/info/landing/LandingPage";
import { NoWorkspace } from "@/components/workspace/NoWorkspace";
import { useLoginNavigation } from "@/hooks/use-login-navigation";
import { useAuth } from "@/integrations/auth/AuthContext";
import { consentIsPending, resolveCurrentUser } from "@/integrations/auth/guard";

export const Route = createFileRoute("/")({
	staticData: { surface: "bleed" },
	beforeLoad: async ({ context }) => {
		const user = await resolveCurrentUser(context.queryClient);
		if (!user) return;
		if (await consentIsPending(context.queryClient)) {
			throw redirect({
				to: "/consent",
				search: { returnTo: "/" },
				mask: { to: "/" },
			});
		}
		// A failed workspace query must not render the no-workspace state.
		const workspaces = await context.queryClient.query(listWorkspacesOptions());
		const workspaceSlug = workspaces[0]?.workspaceSlug;
		if (workspaceSlug) {
			throw redirect({ to: "/w/$workspaceSlug", params: { workspaceSlug }, replace: true });
		}
	},
	component: IndexPage,
});

function IndexPage() {
	const { isAuthenticated } = useAuth();
	return isAuthenticated ? (
		<StandardPageSurface className="h-full">
			<NoWorkspace />
		</StandardPageSurface>
	) : (
		<LandingContainer />
	);
}

function LandingContainer() {
	const openLogin = useLoginNavigation();
	return <LandingPage onSignIn={openLogin} />;
}
