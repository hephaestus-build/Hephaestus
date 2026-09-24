import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowLeftIcon, type LucideIcon, OctagonXIcon } from "lucide-react";
import type { ReactNode } from "react";

import { getProvidersOptions } from "@/api/@tanstack/react-query.gen";
import { type BrandIcon, GithubIcon, GitlabIcon } from "@/components/icons/brand";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

export const Route = createFileRoute("/_authenticated/workspaces/new/")({
	component: ProviderSelectionPage,
});

interface Provider {
	id: string;
	name: string;
	description: string;
	/**
	 * Lucide and the in-house brand glyphs are separate component types, and this slot takes either.
	 * All it asks of a glyph is that it render from `className`, which both do.
	 */
	icon: LucideIcon | BrandIcon;
	to: string;
}

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

	// Mirror the server gate (hephaestus.workspace.creation-policy): under ADMIN_ONLY, a non-admin
	// would get a 403 on submit, so surface that up front instead of letting them fill out the wizard.
	const adminOnly = workspaceProviders?.creationPolicy === "ADMIN_ONLY";
	const blockedForNonAdmin = adminOnly && !isAppAdmin;

	const providers: Provider[] = [];
	// The GitHub card leads to a page whose only action is the installation link, so a provider
	// without a usable URL is a dead end rather than a choice.
	if (hasText(workspaceProviders?.github?.appInstallationUrl)) {
		providers.push({
			id: "github",
			name: "GitHub",
			description:
				"Install the Hephaestus GitHub App on your organization. Workspaces are created automatically.",
			icon: GithubIcon,
			to: "/workspaces/new/github",
		});
	}
	if (workspaceProviders?.gitlab) {
		providers.push({
			id: "gitlab",
			name: "GitLab",
			description: "Connect with an access token and select a group to monitor.",
			icon: GitlabIcon,
			to: "/workspaces/new/gitlab",
		});
	}

	let providerChoice: ReactNode;
	if (isLoading) {
		providerChoice = (
			<div className="flex justify-center py-12">
				<Spinner />
			</div>
		);
	} else if (blockedForNonAdmin) {
		providerChoice = (
			<Alert className="mb-4">
				<OctagonXIcon aria-hidden="true" />
				<AlertTitle>Workspace creation is admin-only</AlertTitle>
				<AlertDescription>
					An instance admin must create workspaces on this deployment. Ask an admin to set one up
					for you.
				</AlertDescription>
			</Alert>
		);
	} else if (providers.length === 0 && !isError) {
		providerChoice = (
			<p className="py-12 text-center text-muted-foreground">
				No providers are currently available. Contact your administrator.
			</p>
		);
	} else {
		providerChoice = (
			<div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
				{providers.map((provider) => (
					<Link
						key={provider.id}
						to={provider.to}
						aria-label={`Set up workspace with ${provider.name}`}
					>
						<Card variant="interactive" className="h-full cursor-pointer">
							<CardHeader>
								<div className="mb-1 flex items-center gap-3">
									<provider.icon className="size-6" />
									<CardTitle className="text-lg">{provider.name}</CardTitle>
								</div>
								<CardDescription>{provider.description}</CardDescription>
							</CardHeader>
						</Card>
					</Link>
				))}
			</div>
		);
	}

	return (
		<div className="mx-auto w-full max-w-2xl">
			<Link
				to="/"
				className="mb-6 inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
				aria-label="Back to dashboard"
			>
				<ArrowLeftIcon className="size-3.5" />
				Back
			</Link>
			<div className="mb-8 space-y-1.5">
				<h1 className="text-2xl font-semibold tracking-tight">Create Workspace</h1>
				<p className="text-muted-foreground">Choose your Git provider to get started.</p>
			</div>
			{isError && (
				<Alert variant="destructive" className="mb-4">
					<OctagonXIcon aria-hidden="true" />
					<AlertTitle>Load failure</AlertTitle>
					<AlertDescription>
						Could not load provider options. Try refreshing the page.
					</AlertDescription>
				</Alert>
			)}
			{providerChoice}
		</div>
	);
}
