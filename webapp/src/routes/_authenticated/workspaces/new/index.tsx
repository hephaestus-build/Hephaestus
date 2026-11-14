import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowLeftIcon } from "lucide-react";

import { getProvidersOptions } from "@/api/@tanstack/react-query.gen";
import {
	type WorkspaceProvider,
	WorkspaceProviderChoice,
} from "@/components/create-workspace/WorkspaceProviderChoice";
import { workspaceCreationOf } from "@/hooks/use-workspace-creation";
import { pageHead } from "@/lib/page-title";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

export const Route = createFileRoute("/_authenticated/workspaces/new/")({
	head: pageHead("Create workspace"),
	component: ProviderSelectionPage,
});

function ProviderSelectionPage() {
	const { isAppAdmin } = useAuth();
	const {
		data: workspaceProviders,
		isLoading,
		isError,
	} = useQuery({
		...getProvidersOptions(),
		staleTime: 5 * 60 * 1000,
	});

	const providers: WorkspaceProvider[] = [];
	// The GitHub card leads to a page whose only action is the installation link, so a provider
	// without a usable URL is a dead end rather than a choice.
	if (hasText(workspaceProviders?.github?.appInstallationUrl)) {
		providers.push("github");
	}
	if (workspaceProviders?.gitlab) {
		providers.push("gitlab");
	}

	return (
		<div className="mx-auto w-full max-w-2xl">
			<Link
				to="/"
				className="mb-6 inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
			>
				<ArrowLeftIcon className="size-3.5" />
				Back to app
			</Link>
			<div className="mb-8 space-y-1.5">
				<h1 className="text-2xl font-semibold tracking-tight">Create workspace</h1>
				<p className="text-muted-foreground">Choose your Git provider to get started.</p>
			</div>
			<WorkspaceProviderChoice
				providers={providers}
				loading={isLoading}
				failed={isError}
				// A non-admin under ADMIN_ONLY would get a 403 on submit, so say so before the wizard.
				adminOnly={
					workspaceCreationOf(workspaceProviders?.creationPolicy, isAppAdmin) ===
					"instance-admin-only"
				}
			/>
		</div>
	);
}
