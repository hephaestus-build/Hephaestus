import { Link } from "@tanstack/react-router";
import { OctagonXIcon } from "lucide-react";

import { type BrandIcon, GitHubIcon, GitLabIcon } from "@/components/icons/brand";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

import { ProviderChoiceSkeleton } from "./CreateWorkspaceSkeletons";

export type WorkspaceProvider = "github" | "gitlab";

const PROVIDERS = {
	github: {
		name: "GitHub",
		description:
			"Install the Hephaestus GitHub App on your organization. Workspaces are created automatically.",
		icon: GitHubIcon,
		to: "/workspaces/new/github",
	},
	gitlab: {
		name: "GitLab",
		description: "Connect with an access token and select a group to monitor.",
		icon: GitLabIcon,
		to: "/workspaces/new/gitlab",
	},
} as const satisfies Record<
	WorkspaceProvider,
	{ name: string; description: string; icon: BrandIcon; to: string }
>;

export interface WorkspaceProviderChoiceProps {
	/** The providers this instance can create a workspace from, in the order they show. */
	providers: readonly WorkspaceProvider[];
	/** The provider options are still loading. */
	loading?: boolean;
	/** The provider options could not be loaded. */
	failed?: boolean;
	/** Only an instance admin may create a workspace here, and the reader is not one. */
	adminOnly?: boolean;
}

/**
 * The first step of creating a workspace: one card per provider the instance offers. Each card is a
 * link named by all the text on it, the provider first, so its name is what the reader sees and can
 * say (WCAG 2.2 SC 2.5.3).
 */
export function WorkspaceProviderChoice({
	providers,
	loading = false,
	failed = false,
	adminOnly = false,
}: WorkspaceProviderChoiceProps) {
	if (loading) {
		return <ProviderChoiceSkeleton />;
	}
	if (adminOnly) {
		return (
			<Alert className="mb-4">
				<OctagonXIcon aria-hidden="true" />
				<AlertTitle>Workspace creation is admin-only</AlertTitle>
				<AlertDescription>
					An instance admin must create workspaces on this instance. Ask an instance admin to set
					one up for you.
				</AlertDescription>
			</Alert>
		);
	}
	return (
		<>
			{failed && (
				<Alert variant="destructive" className="mb-4">
					<OctagonXIcon aria-hidden="true" />
					<AlertTitle>We could not load the provider options</AlertTitle>
					<AlertDescription>Reload the page to try again.</AlertDescription>
				</Alert>
			)}
			{providers.length === 0 && !failed && (
				<p className="py-12 text-center text-muted-foreground">
					No providers are available. Contact an instance admin.
				</p>
			)}
			{providers.length > 0 && (
				<div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
					{providers.map((provider) => (
						<ProviderCard key={provider} provider={provider} />
					))}
				</div>
			)}
		</>
	);
}

function ProviderCard({ provider }: { provider: WorkspaceProvider }) {
	const { name, description, icon: Icon, to } = PROVIDERS[provider];
	return (
		<Link to={to}>
			<Card variant="interactive" className="h-full cursor-pointer">
				<CardHeader>
					<div className="mb-1 flex items-center gap-3">
						<Icon className="size-6" aria-hidden="true" />
						<CardTitle className="text-lg">{name}</CardTitle>
					</div>
					<CardDescription>{description}</CardDescription>
				</CardHeader>
			</Card>
		</Link>
	);
}
