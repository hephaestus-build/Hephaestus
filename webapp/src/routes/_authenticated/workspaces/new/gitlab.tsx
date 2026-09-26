import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, Navigate, useNavigate } from "@tanstack/react-router";
import { ArrowLeftIcon, OctagonXIcon } from "lucide-react";
import { type ReactNode, useEffect, useReducer, useRef } from "react";
import { toast } from "sonner";

import {
	createWorkspaceMutation,
	getProvidersOptions,
	listGitLabGroupsMutation,
	listIdentityProvidersOptions,
	listWorkspacesQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { WorkspaceListItem } from "@/api/types.gen";
import { ConfigureWorkspaceStep } from "@/components/create-workspace/ConfigureWorkspaceStep";
import { ConnectGitLabStep } from "@/components/create-workspace/ConnectGitLabStep";
import { workspaceDetailsSchema } from "@/components/create-workspace/schemas";
import { SelectGroupStep } from "@/components/create-workspace/SelectGroupStep";
import {
	createInitialWizardState,
	isForCurrentToken,
	WizardContext,
	type WizardStep,
	wizardReducer,
} from "@/components/create-workspace/wizard-context";
import { WizardStepIndicator } from "@/components/create-workspace/WizardStepIndicator";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { problemDetailOf } from "@/lib/problem-detail";
import { firstNonBlank, hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

export const Route = createFileRoute("/_authenticated/workspaces/new/gitlab")({
	component: GitLabWizardPage,
});

const STEP_META: Record<WizardStep, { title: string; description: string }> = {
	1: {
		title: "Connect to GitLab",
		description: "Enter an access token for the GitLab instance you'll monitor.",
	},
	2: { title: "Select a Group", description: "Choose the GitLab group to monitor." },
	3: { title: "Configure Workspace", description: "Set a name and URL slug for your workspace." },
};

interface GitLabProvider {
	registrationId: string;
	displayName: string;
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
 * Shown when no GitLab sign-in is configured for the instance workspaces are created on, so there is
 * nothing to link. An admin must add a GitLab login provider for it first (Instance admin → Login
 * providers).
 */
function NoGitLabProviderNotice({
	serverUrl,
	isAppAdmin,
}: {
	serverUrl: string;
	isAppAdmin: boolean;
}) {
	return (
		<div className="mx-auto w-full max-w-2xl">
			<BackToProviders />
			<div className="space-y-4">
				<h1 className="text-2xl font-semibold tracking-tight">GitLab sign-in isn’t configured</h1>
				<p className="text-muted-foreground">
					GitLab workspaces are created on {serverUrl}, which has no GitLab login provider, so a
					GitLab account there can’t be linked yet.
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
 * Prompts the user to link their GitLab account on the instance workspaces are created on, via re-login
 * linking: a top-level redirect to that instance that attaches the identity to the current account.
 */
function GitLabLinkPrompt({
	serverUrl,
	providers,
	linkAccount,
}: {
	serverUrl: string;
	providers: GitLabProvider[];
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
						To create a GitLab workspace, link your GitLab account on {serverUrl} first. You’ll be
						redirected to GitLab to sign in; the identity is then attached to your current account.
					</p>
				</div>
				<div className="flex flex-col items-start gap-2">
					{providers.map((provider) => (
						<Button
							key={provider.registrationId}
							onClick={() => linkAccount(provider.registrationId)}
						>
							{multiple ? `Link ${provider.displayName}` : "Link GitLab account"}
						</Button>
					))}
				</div>
			</div>
		</div>
	);
}

/** Keyed on the step, so each step's heading mounts fresh and takes focus for a screen reader to announce. */
function StepHeading({ children }: { children: ReactNode }) {
	const headingRef = useRef<HTMLHeadingElement>(null);
	useEffect(() => {
		headingRef.current?.focus();
	}, []);
	return (
		<h1
			id="wizard-heading"
			ref={headingRef}
			tabIndex={-1}
			className="text-2xl font-semibold tracking-tight outline-none"
		>
			{children}
		</h1>
	);
}

function GitLabWizardPage() {
	const { linkAccount, linkedProviders, isAppAdmin } = useAuth();

	const {
		data: providers,
		isLoading: providersLoading,
		isError: providersError,
	} = useQuery({
		...getProvidersOptions(),
		staleTime: 5 * 60 * 1000,
	});

	const {
		data: identityProviders,
		isLoading: identityProvidersLoading,
		isError: identityProvidersError,
	} = useQuery({
		...listIdentityProvidersOptions(),
		staleTime: 5 * 60 * 1000,
	});

	if (providersLoading || identityProvidersLoading) {
		return (
			<div className="flex justify-center py-16">
				<Spinner />
			</div>
		);
	}
	if (providersError || identityProvidersError) {
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
	const serverUrl = providers?.gitlab?.defaultServerUrl;
	if (!hasText(serverUrl)) {
		return <Navigate to="/workspaces/new" />;
	}

	// Workspaces are created on the one GitLab instance the server syncs, and owned by the account's
	// identity there. The server sends every URL here as an origin, so plain comparison matches.
	const instanceLogins: GitLabProvider[] = (identityProviders ?? []).flatMap((p) =>
		p.providerType === "GITLAB" && hasText(p.registrationId) && p.baseUrl === serverUrl
			? [
					{
						registrationId: p.registrationId,
						displayName: firstNonBlank(p.displayName) ?? p.registrationId,
					},
				]
			: [],
	);
	const linked = linkedProviders.some((p) => p.type === "GITLAB" && p.serverUrl === serverUrl);

	if (!linked && instanceLogins.length === 0) {
		return <NoGitLabProviderNotice serverUrl={serverUrl} isAppAdmin={isAppAdmin} />;
	}
	if (!linked) {
		return (
			<GitLabLinkPrompt
				serverUrl={serverUrl}
				providers={instanceLogins}
				linkAccount={linkAccount}
			/>
		);
	}

	return <GitLabWizard serverUrl={serverUrl} />;
}

/** Mounted once the server has named its GitLab instance, so no request goes anywhere else. */
function GitLabWizard({ serverUrl }: { serverUrl: string }) {
	const [state, dispatch] = useReducer(wizardReducer, serverUrl, createInitialWizardState);
	const queryClient = useQueryClient();
	const navigate = useNavigate();
	const stepAnnouncement = `Step ${state.step} of 3: ${STEP_META[state.step].title}`;

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
			toast.error(problemDetailOf(error, "Failed to create workspace. Please try again."));
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

	const handleNext = () => {
		if (state.step === 1 && canAdvanceFromStep1) {
			if (listGroups.isPending) {
				return;
			}
			listGroups.mutate(
				{
					body: {
						personalAccessToken: state.personalAccessToken,
						serverUrl: state.serverUrl,
					},
				},
				{
					onSuccess: (groups, { body }) => {
						dispatch({ type: "ADVANCE_TO_GROUPS", groups, request: body });
					},
				},
			);
		} else if (state.step === 2 && canAdvanceFromStep2) {
			dispatch({ type: "ADVANCE_TO_CONFIGURE" });
		}
	};

	const handleBack = () => {
		// Reset stale mutation state so old errors don't persist after back-navigation
		if (state.step === 2) {
			listGroups.reset();
		}
		dispatch({ type: "GO_BACK" });
	};

	const handleSubmit = () => {
		if (!canSubmit || !state.selectedGroup || createWorkspace.isPending) {
			return;
		}
		createWorkspace.mutate({
			body: {
				workspaceSlug: state.workspaceSlug,
				displayName: state.displayName,
				accountLogin: state.selectedGroup.fullPath,
				accountType: "ORG",
				kind: "GITLAB",
				personalAccessToken: state.personalAccessToken,
				serverUrl: state.serverUrl,
			},
		});
	};

	const meta = STEP_META[state.step];
	const wizardContextValue = { state, dispatch };
	const isTransitioning = listGroups.isPending;
	const isCreating = createWorkspace.isPending;
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
				<StepHeading key={state.step}>{meta.title}</StepHeading>
				<p className="text-muted-foreground">{meta.description}</p>
			</div>

			<WizardStepIndicator currentStep={state.step} />

			<div className="mt-6" role="region" aria-labelledby="wizard-heading">
				<WizardContext.Provider value={wizardContextValue}>
					{state.step === 1 && <ConnectGitLabStep />}
					{state.step === 2 && <SelectGroupStep />}
					{state.step === 3 && <ConfigureWorkspaceStep />}
				</WizardContext.Provider>
			</div>

			{listGroups.isError &&
				state.step === 1 &&
				isForCurrentToken(state, listGroups.variables.body) && (
					<Alert variant="destructive" className="mt-4">
						<OctagonXIcon aria-hidden="true" />
						<AlertTitle>Failed to load groups</AlertTitle>
						<AlertDescription>
							{problemDetailOf(
								listGroups.error,
								"GitLab did not return your groups. Try again in a moment.",
							)}
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
