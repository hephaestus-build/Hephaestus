import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, Navigate, useNavigate } from "@tanstack/react-router";
import { ArrowLeftIcon, OctagonXIcon } from "lucide-react";
import { useEffect, useReducer, useRef } from "react";
import { toast } from "sonner";

import {
	createWorkspaceMutation,
	getProvidersOptions,
	listGitLabGroupsMutation,
	listIdentityProvidersOptions,
	listWorkspacesQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { WorkspaceListItem } from "@/api/types.gen";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { ConfigureWorkspaceStep } from "@/components/workspace/create-workspace/ConfigureWorkspaceStep";
import { ConnectGitLabStep } from "@/components/workspace/create-workspace/ConnectGitLabStep";
import { workspaceDetailsSchema } from "@/components/workspace/create-workspace/schemas";
import { SelectGroupStep } from "@/components/workspace/create-workspace/SelectGroupStep";
import {
	createInitialWizardState,
	WizardContext,
	type WizardStep,
	wizardReducer,
} from "@/components/workspace/create-workspace/wizard-context";
import { WizardStepIndicator } from "@/components/workspace/create-workspace/WizardStepIndicator";
import { useAuth } from "@/integrations/auth/AuthContext";
import { isRecord } from "@/lib/is-record";
import { firstNonBlank } from "@/lib/text";

export const Route = createFileRoute("/_authenticated/workspaces/new/gitlab")({
	component: GitLabWizardPage,
});

const STEP_META: Record<WizardStep, { title: string; description: string }> = {
	1: {
		title: "Connect to GitLab",
		description: "Enter your GitLab instance URL and access token.",
	},
	2: { title: "Select a Group", description: "Choose the GitLab group to monitor." },
	3: { title: "Configure Workspace", description: "Set a name and URL slug for your workspace." },
};

interface GitLabProvider {
	registrationId: string;
	displayName: string;
	baseUrl: string;
}

function BackToProviders() {
	return (
		<Link
			to="/workspaces/new"
			className="mb-6 inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
		>
			<ArrowLeftIcon className="size-3.5" />
			Back
		</Link>
	);
}

/**
 * Shown when no GitLab sign-in is configured for this instance, so there is nothing to link. An admin
 * must add a GitLab login provider first (Instance admin → Login providers).
 */
function NoGitLabProviderNotice({ isAppAdmin }: { isAppAdmin: boolean }) {
	return (
		<div className="mx-auto w-full max-w-2xl">
			<BackToProviders />
			<div className="space-y-4">
				<h1 className="text-2xl font-semibold tracking-tight">GitLab sign-in isn't configured</h1>
				<p className="text-muted-foreground">
					This instance has no GitLab login provider, so a GitLab account can't be linked yet.
					{isAppAdmin
						? " Add one to enable GitLab sign-in."
						: " Ask an instance admin to add one (Instance admin → Login providers)."}
				</p>
				{isAppAdmin && (
					<Link to="/admin/login-providers" className={buttonVariants({ className: "w-fit" })}>
						Manage login providers
					</Link>
				)}
			</div>
		</div>
	);
}

/**
 * Prompts the user to link a GitLab account via re-login linking: a top-level redirect to the GitLab
 * identity provider that attaches the identity to the current account. When more than one GitLab
 * instance is configured, the user picks which instance to link (no arbitrary default).
 */
function GitLabLinkPrompt({
	providers,
	linkedServerUrls,
	linkAccount,
}: {
	providers: GitLabProvider[];
	linkedServerUrls: Set<string>;
	linkAccount: (alias: string) => void;
}) {
	const multiple = providers.length > 1;
	return (
		<div className="mx-auto w-full max-w-2xl">
			<BackToProviders />
			<div className="space-y-4">
				<div className="space-y-1.5">
					<h1 className="text-2xl font-semibold tracking-tight">Link your GitLab account</h1>
					<p className="text-muted-foreground">
						{multiple
							? "To create a GitLab workspace, link the GitLab instance you'll monitor. You'll be redirected to sign in; the identity is then attached to your current account."
							: "To create a GitLab workspace, link your GitLab account first. You'll be redirected to GitLab to sign in; the identity is then attached to your current account."}
					</p>
				</div>
				<div className="flex flex-col items-start gap-2">
					{providers.map((provider) => {
						const linked = linkedServerUrls.has(provider.baseUrl);
						return (
							<Button
								key={provider.registrationId}
								variant={linked ? "outline" : "default"}
								disabled={linked}
								onClick={() => linkAccount(provider.registrationId)}
							>
								{linked
									? `${provider.displayName} — already linked`
									: multiple
										? `Link ${provider.displayName}`
										: "Link GitLab account"}
							</Button>
						);
					})}
				</div>
			</div>
		</div>
	);
}

function GitLabWizardPage() {
	const { hasGitLabIdentity, linkAccount, linkedProviders, isAppAdmin } = useAuth();

	const {
		data: providers,
		isLoading: providersLoading,
		isError: providersError,
	} = useQuery({
		...getProvidersOptions(),
		staleTime: 5 * 60 * 1000,
	});

	// The configured GitLab sign-in options (one per instance). Used to gate + drive instance-scoped
	// account linking — never an arbitrary "first gitlab-ish" provider.
	const { data: identityProviders } = useQuery({
		...listIdentityProvidersOptions(),
		staleTime: 5 * 60 * 1000,
	});
	const gitlabProviders: GitLabProvider[] = (identityProviders ?? []).flatMap((p) => {
		if (p.providerType !== "GITLAB" || !p.registrationId) return [];
		return [
			{
				registrationId: p.registrationId,
				displayName: firstNonBlank(p.displayName) ?? p.registrationId,
				baseUrl: p.baseUrl ?? "",
			},
		];
	});
	const linkedGitlabServerUrls = new Set(
		linkedProviders.flatMap((p) => (p.type === "GITLAB" && p.serverUrl ? [p.serverUrl] : [])),
	);

	const gitlabEnabled = !!providers?.gitlab;
	const defaultServerUrl = providers?.gitlab?.defaultServerUrl;

	const [state, dispatch] = useReducer(wizardReducer, defaultServerUrl, (url) =>
		createInitialWizardState(url),
	);
	const queryClient = useQueryClient();
	const navigate = useNavigate();
	const headingRef = useRef<HTMLHeadingElement>(null);

	const stepAnnouncement = `Step ${state.step} of 3: ${STEP_META[state.step].title}`;

	// No `onError`: the alert is rendered off `listGroups.isError` beside step 1's server URL and
	// token, the two fields a reader can act on.
	const listGroups = useMutation(listGitLabGroupsMutation());

	const createWorkspace = useMutation({
		...createWorkspaceMutation(),
		onSuccess: (data) => {
			queryClient.setQueryData<WorkspaceListItem[]>(listWorkspacesQueryKey(), (workspaces) => [
				...(workspaces ?? []),
				data,
			]);
			toast.success(`Workspace "${data.displayName}" created`);
			void navigate({
				to: "/w/$workspaceSlug",
				params: { workspaceSlug: data.workspaceSlug },
			});
		},
		onError: (error) => {
			const rawError = isRecord(error) ? error.error : undefined;
			toast.error(
				typeof rawError === "string" ? rawError : "Failed to create workspace. Please try again.",
			);
		},
		onSettled: () => {
			void queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() });
		},
	});

	const canAdvanceFromStep1 = state.preflightResult?.valid === true;
	const canAdvanceFromStep2 = state.selectedGroup !== null;
	const canSubmit =
		state.step === 3 &&
		state.selectedGroup !== null &&
		workspaceDetailsSchema.safeParse({
			displayName: state.displayName,
			workspaceSlug: state.workspaceSlug,
		}).success;

	// Focus moves to the new step's heading so a screen reader announces where the wizard now is.
	useEffect(() => {
		headingRef.current?.focus();
	}, [state.step]);

	const handleNext = () => {
		if (state.step === 1 && canAdvanceFromStep1) {
			if (listGroups.isPending) return;
			listGroups.mutate(
				{
					body: {
						personalAccessToken: state.personalAccessToken,
						serverUrl: state.serverUrl || undefined,
					},
				},
				{
					onSuccess: (data) => {
						dispatch({ type: "ADVANCE_TO_GROUPS", groups: data });
					},
				},
			);
		} else if (state.step === 2 && canAdvanceFromStep2) {
			dispatch({ type: "ADVANCE_TO_CONFIGURE" });
		}
	};

	const handleBack = () => {
		// Reset stale mutation state so old errors don't persist after back-navigation
		if (state.step === 2) listGroups.reset();
		dispatch({ type: "GO_BACK" });
	};

	const handleSubmit = () => {
		if (!canSubmit || !state.selectedGroup || createWorkspace.isPending) return;
		createWorkspace.mutate({
			body: {
				workspaceSlug: state.workspaceSlug,
				displayName: state.displayName,
				accountLogin: state.selectedGroup.fullPath,
				accountType: "ORG",
				kind: "GITLAB",
				personalAccessToken: state.personalAccessToken,
				serverUrl: state.serverUrl || undefined,
			},
		});
	};

	const meta = STEP_META[state.step];
	const wizardContextValue = { state, dispatch };
	const isTransitioning = listGroups.isPending;
	const isCreating = createWorkspace.isPending;
	if (providersLoading) {
		return (
			<div className="flex justify-center py-16">
				<Spinner />
			</div>
		);
	}
	if (providersError) {
		return (
			<div className="mx-auto w-full max-w-2xl">
				<Alert variant="destructive">
					<OctagonXIcon aria-hidden="true" />
					<AlertTitle>Unable to load</AlertTitle>
					<AlertDescription>
						Could not check feature availability. Please refresh the page.
					</AlertDescription>
				</Alert>
			</div>
		);
	}
	if (!gitlabEnabled) {
		return <Navigate to="/workspaces/new" />;
	}

	if (gitlabProviders.length === 0) {
		return <NoGitLabProviderNotice isAppAdmin={isAppAdmin} />;
	}

	if (!hasGitLabIdentity) {
		return (
			<GitLabLinkPrompt
				providers={gitlabProviders}
				linkedServerUrls={linkedGitlabServerUrls}
				linkAccount={linkAccount}
			/>
		);
	}

	return (
		<div className="mx-auto w-full max-w-2xl">
			{/* Visually hidden live region for screen reader step announcements */}
			<div aria-live="polite" aria-atomic="true" className="sr-only">
				{stepAnnouncement}
			</div>

			<Link
				to="/workspaces/new"
				className="mb-6 inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
				aria-label="Back to provider selection"
			>
				<ArrowLeftIcon className="size-3.5" />
				Back
			</Link>

			<div className="mb-6 space-y-1.5">
				<h1
					id="wizard-heading"
					ref={headingRef}
					tabIndex={-1}
					className="text-2xl font-semibold tracking-tight outline-none"
				>
					{meta.title}
				</h1>
				<p className="text-muted-foreground">{meta.description}</p>
			</div>

			<WizardStepIndicator currentStep={state.step} />

			<div className="mt-6" role="region" aria-labelledby="wizard-heading">
				<WizardContext.Provider value={wizardContextValue}>
					{state.step === 1 && <ConnectGitLabStep instances={gitlabProviders} />}
					{state.step === 2 && <SelectGroupStep />}
					{state.step === 3 && <ConfigureWorkspaceStep />}
				</WizardContext.Provider>
			</div>

			{listGroups.isError && state.step === 1 && (
				<Alert variant="destructive" className="mt-4">
					<OctagonXIcon aria-hidden="true" />
					<AlertTitle>Failed to load groups</AlertTitle>
					<AlertDescription>
						Could not load groups. Check your connection, server URL, and token permissions.
					</AlertDescription>
				</Alert>
			)}

			<div className="mt-6 flex justify-end gap-2">
				{state.step > 1 && (
					<Button
						variant="outline"
						onClick={handleBack}
						disabled={isCreating}
						aria-label="Back to previous step"
					>
						Back
					</Button>
				)}
				{state.step < 3 && (
					<Button
						onClick={handleNext}
						disabled={
							(state.step === 1 && !canAdvanceFromStep1) ||
							(state.step === 2 && !canAdvanceFromStep2) ||
							isTransitioning
						}
					>
						{isTransitioning && <Spinner className="mr-2" />}
						Next
					</Button>
				)}
				{state.step === 3 && (
					<Button onClick={handleSubmit} disabled={!canSubmit || isCreating}>
						{isCreating && <Spinner className="mr-2" />}
						Create Workspace
					</Button>
				)}
			</div>
		</div>
	);
}
