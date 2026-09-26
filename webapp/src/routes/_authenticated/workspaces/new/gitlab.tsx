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
	isForCurrentCredentials,
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
	baseUrl: string;
}

/**
 * The key GitLab instances are compared by here, matching the server's: a URL origin lower-cases scheme
 * and host, writes an IPv6 address one way and drops a default port. An IPv4-mapped IPv6 address has no
 * key on the server, so it has none here either.
 */
function instanceOrigin(url: string | undefined): string | undefined {
	const parsed = URL.parse(url ?? "");
	return parsed === null || parsed.hostname.startsWith("[::ffff:") ? undefined : parsed.origin;
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

/** A GitLab sign-in problem only an instance admin can fix, under Instance admin → Login providers. */
function GitLabSetupNotice({
	title,
	children,
	isAppAdmin,
}: {
	title: string;
	children: ReactNode;
	isAppAdmin: boolean;
}) {
	return (
		<div className="mx-auto w-full max-w-2xl">
			<BackToProviders />
			<div className="space-y-4">
				<h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
				<p className="text-muted-foreground">
					{children}{" "}
					{isAppAdmin
						? "Fix it under Login providers."
						: "Ask an instance admin to fix it (Instance admin → Login providers)."}
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
function linkLabel(displayName: string, linked: boolean, multiple: boolean) {
	if (linked) {
		return `${displayName} — already linked`;
	}
	return multiple ? `Link ${displayName}` : "Link GitLab account";
}

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
								{linkLabel(provider.displayName, linked, multiple)}
							</Button>
						);
					})}
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

	// One GitLab sign-in per configured instance: the only instances a token may be sent to.
	const {
		data: identityProviders,
		isLoading: identityProvidersLoading,
		isError: identityProvidersError,
	} = useQuery({
		...listIdentityProvidersOptions(),
		staleTime: 5 * 60 * 1000,
	});
	const gitlabProviders: GitLabProvider[] = (identityProviders ?? []).flatMap((p) => {
		const baseUrl = instanceOrigin(p.baseUrl);
		if (p.providerType !== "GITLAB" || !hasText(p.registrationId) || !hasText(baseUrl)) {
			return [];
		}
		return [
			{
				registrationId: p.registrationId,
				displayName: firstNonBlank(p.displayName) ?? p.registrationId,
				baseUrl,
			},
		];
	});
	const linkedGitlabServerUrls = new Set(
		linkedProviders.flatMap((p) => {
			const origin = instanceOrigin(p.serverUrl);
			return p.type === "GITLAB" && hasText(origin) ? [origin] : [];
		}),
	);

	const gitlabEnabled = Boolean(providers?.gitlab);
	const defaultServerUrl = instanceOrigin(providers?.gitlab?.defaultServerUrl);
	// A workspace is owned by the account's identity on its own instance, so only linked ones are offered.
	const linkedInstances = gitlabProviders.filter((p) => linkedGitlabServerUrls.has(p.baseUrl));
	const defaultInstance =
		linkedInstances.find((p) => p.baseUrl === defaultServerUrl) ?? linkedInstances.at(0);

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
	if (!gitlabEnabled) {
		return <Navigate to="/workspaces/new" />;
	}

	if (gitlabProviders.length === 0) {
		return (
			<GitLabSetupNotice title="GitLab sign-in isn’t configured" isAppAdmin={isAppAdmin}>
				This instance has no GitLab login provider, so a GitLab account can’t be linked yet.
			</GitLabSetupNotice>
		);
	}

	// The server refuses creation on such an instance too.
	const duplicated = gitlabProviders.find(
		(p, index) => gitlabProviders.findIndex((other) => other.baseUrl === p.baseUrl) !== index,
	);
	if (duplicated !== undefined) {
		return (
			<GitLabSetupNotice title="GitLab sign-in is configured twice" isAppAdmin={isAppAdmin}>
				More than one GitLab login provider signs in to {duplicated.baseUrl}, so Hephaestus can’t
				tell which of your GitLab accounts there should own a workspace. One of them must be
				disabled before GitLab workspaces can be created.
			</GitLabSetupNotice>
		);
	}

	if (defaultInstance === undefined) {
		return (
			<GitLabLinkPrompt
				providers={gitlabProviders}
				linkedServerUrls={linkedGitlabServerUrls}
				linkAccount={linkAccount}
			/>
		);
	}

	return (
		<GitLabWizard
			instances={linkedInstances}
			unlinkedInstances={gitlabProviders.filter((p) => !linkedGitlabServerUrls.has(p.baseUrl))}
			linkAccount={linkAccount}
			initialServerUrl={defaultInstance.baseUrl}
		/>
	);
}

/** Mounted once the configured instances are known, so the wizard starts on one of them. */
function GitLabWizard({
	instances,
	unlinkedInstances,
	linkAccount,
	initialServerUrl,
}: {
	instances: GitLabProvider[];
	unlinkedInstances: GitLabProvider[];
	linkAccount: (alias: string) => void;
	initialServerUrl: string;
}) {
	const [state, dispatch] = useReducer(wizardReducer, initialServerUrl, createInitialWizardState);
	const queryClient = useQueryClient();
	const navigate = useNavigate();
	const stepAnnouncement = `Step ${state.step} of 3: ${STEP_META[state.step].title}`;

	// No `onError`: the alert renders off `listGroups.isError` beside the token a reader can fix.
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
					{state.step === 1 && <ConnectGitLabStep instances={instances} />}
					{state.step === 2 && <SelectGroupStep />}
					{state.step === 3 && <ConfigureWorkspaceStep />}
				</WizardContext.Provider>
			</div>

			{state.step === 1 && unlinkedInstances.length > 0 && (
				<div className="mt-4 flex flex-wrap items-baseline gap-x-3 text-sm text-muted-foreground">
					<span>Only instances your account is linked to are listed.</span>
					{unlinkedInstances.map((instance) => (
						<Button
							key={instance.registrationId}
							variant="link"
							size="inline"
							onClick={() => linkAccount(instance.registrationId)}
						>
							Link {instance.displayName}
						</Button>
					))}
				</div>
			)}

			{listGroups.isError &&
				state.step === 1 &&
				isForCurrentCredentials(state, listGroups.variables.body) && (
					<Alert variant="destructive" className="mt-4">
						<OctagonXIcon aria-hidden="true" />
						<AlertTitle>Failed to load groups</AlertTitle>
						<AlertDescription>
							GitLab did not return your groups. It may be unavailable, or the token may no longer
							be valid. Try again in a moment, or validate the token again.
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
