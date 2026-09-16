import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowLeftIcon, ExternalLinkIcon } from "lucide-react";

import { getProvidersOptions } from "@/api/@tanstack/react-query.gen";
import { GithubIcon } from "@/components/icons/brand";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { buttonVariants } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

export const Route = createFileRoute("/_authenticated/workspaces/new/github")({
	component: GitHubSetupPage,
});

function GitHubSetupPage() {
	const { data: providers, isLoading } = useQuery({
		...getProvidersOptions(),
		staleTime: 5 * 60 * 1000,
	});

	const appUrl = providers?.github?.appInstallationUrl;

	return (
		<div className="mx-auto w-full max-w-2xl">
			<Link
				to="/workspaces/new"
				className="mb-6 inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
			>
				<ArrowLeftIcon className="size-3.5" />
				Back
			</Link>

			<div className="mb-6 flex items-center gap-3">
				<GithubIcon className="size-8" />
				<div>
					<h1 className="text-2xl font-semibold tracking-tight">Connect GitHub</h1>
					<p className="text-muted-foreground">
						GitHub workspaces are created automatically via the Hephaestus GitHub App.
					</p>
				</div>
			</div>

			<div className="space-y-4">
				<div className="space-y-3 rounded-lg border p-4">
					<h2 className="font-medium">How it works</h2>
					<ol className="list-inside list-decimal space-y-2 text-sm text-muted-foreground">
						<li>
							Install the <strong className="text-foreground">Hephaestus GitHub App</strong> on your
							organization
						</li>
						<li>Select which repositories you want to monitor</li>
						<li>A workspace is created automatically and appears in your sidebar</li>
					</ol>
				</div>

				{isLoading ? (
					<div className="flex justify-center py-4">
						<Spinner />
					</div>
				) : appUrl ? (
					<a
						href={appUrl}
						target="_blank"
						rel="noopener noreferrer"
						className={buttonVariants({ className: "w-full" })}
					>
						<GithubIcon className="mr-2 size-4" />
						Install GitHub App
						<ExternalLinkIcon className="ml-2 size-3.5" />
					</a>
				) : (
					<Alert>
						<AlertTitle>GitHub App not configured</AlertTitle>
						<AlertDescription>
							The GitHub App installation URL has not been configured for this deployment. Contact
							your administrator.
						</AlertDescription>
					</Alert>
				)}
			</div>
		</div>
	);
}
