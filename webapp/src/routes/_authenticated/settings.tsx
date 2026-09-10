import { type DefaultError, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "sonner";

import {
	getConsentStatusOptions,
	getConsentStatusQueryKey,
	getCurrentUserQueryKey,
	getSlackUserPreferencesOptions,
	getSlackUserPreferencesQueryKey,
	getUserSettingsOptions,
	getUserSettingsQueryKey,
	listIdentityProvidersOptions,
	listLinkedIdentitiesOptions,
	listLinkedIdentitiesQueryKey,
	unlinkIdentityMutation,
	updateResearchConsentMutation,
	updateSlackUserPreferencesMutation,
	updateUserSettingsMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Options } from "@/api/sdk.gen";
import type {
	SlackUserPreferences,
	UpdateUserSettingsData,
	UpdateUserSettingsResponse,
	UserSettings,
} from "@/api/types.gen";
import type { LinkedAccountsSectionProps } from "@/components/settings/LinkedAccountsSection";
import { SettingsPage } from "@/components/settings/SettingsPage";
import type { SlackPreferencesSectionProps } from "@/components/settings/SlackPreferencesSection";
import { useAuth } from "@/integrations/auth/AuthContext";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

export const Route = createFileRoute("/_authenticated/settings")({
	component: RouteComponent,
});

function RouteComponent() {
	const queryClient = useQueryClient();
	const { logout, linkAccount } = useAuth();
	const userSettingsQueryKey = getUserSettingsQueryKey();
	const consentQuery = useQuery(getConsentStatusOptions({}));
	const accountConsent = consentQuery.data;

	const {
		data: settings,
		isLoading,
		isError: settingsError,
		refetch: refetchSettings,
	} = useQuery({
		...getUserSettingsOptions({}),
		retry: 1,
	});

	const linkedIdentitiesQuery = useQuery({
		...listLinkedIdentitiesOptions({}),
	});

	const identityProvidersQuery = useQuery({
		...listIdentityProvidersOptions({}),
	});

	const updateSettingsMutation = useMutation<
		UpdateUserSettingsResponse,
		DefaultError,
		Options<UpdateUserSettingsData>,
		{ previousSettings?: UserSettings }
	>({
		...updateUserSettingsMutation(),
		onMutate: async (variables) => {
			await queryClient.cancelQueries({
				queryKey: userSettingsQueryKey,
			});
			const previousSettings = queryClient.getQueryData<UserSettings>(userSettingsQueryKey);
			queryClient.setQueryData(userSettingsQueryKey, variables.body);
			return { previousSettings };
		},
		onError: (_error, _variables, context) => {
			if (context?.previousSettings) {
				queryClient.setQueryData(userSettingsQueryKey, context.previousSettings);
			}
			toast.error("Failed to update settings. Please try again later.");
		},
		onSuccess: (data) => {
			queryClient.setQueryData(userSettingsQueryKey, data);
		},
		onSettled: () => {
			void queryClient.invalidateQueries({
				queryKey: userSettingsQueryKey,
			});
		},
	});

	// Spread-based helper: reads latest cache to avoid stale-closure race under rapid toggling
	const updateSetting = (patch: Partial<UserSettings>) => {
		const current = queryClient.getQueryData<UserSettings>(userSettingsQueryKey);
		if (!current) return;
		updateSettingsMutation.mutate({
			body: { ...current, ...patch },
		});
	};

	const handlePracticeFeedbackToggle = (checked: boolean) =>
		updateSetting({ practiceFeedbackDeliveryEnabled: checked });

	const researchConsentMutation = useMutation({
		...updateResearchConsentMutation(),
		onSuccess: (status) => {
			queryClient.setQueryData(getConsentStatusQueryKey({}), status);
		},
		onError: () => {
			// The refusal may be the notice moving on — a renamed research organisation, or setup owed
			// again. Re-read it so the control reflects what this instance is now asking.
			void queryClient.invalidateQueries({ queryKey: getConsentStatusQueryKey({}) });
			toast.error("Failed to update research participation. Please try again.");
		},
	});

	// Echo the notice and organisation this page rendered: a settings tab left open across a change of
	// research organisation would otherwise record a decision about one the reader never saw.
	const handleResearchToggle = (checked: boolean) =>
		researchConsentMutation.mutate({
			body: {
				granted: checked,
				noticeVersion: accountConsent?.noticeVersion ?? "",
				researchOrganization: accountConsent?.researchOrganization,
			},
		});

	// After deletion: end the session. `logout()` performs a full reload to "/",
	// so no further navigation is needed here.
	const handleAccountDeleted = async () => {
		await logout();
	};

	// Disconnect a federated identity. The server enforces the lockout guard (409 on the last
	// identity) and ownership; the UI also disables that case, so this path is the happy one.
	const unlinkMutation = useMutation({
		...unlinkIdentityMutation(),
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: listLinkedIdentitiesQueryKey({}) });
			// The primary identity (avatar, username) the app shows may have been the one removed.
			void queryClient.invalidateQueries({ queryKey: getCurrentUserQueryKey() });
			toast.success("Account disconnected.");
		},
		onError: (error: DefaultError) => {
			toast.error(problemDetailOf(error, "Couldn't disconnect that account. Please try again."));
		},
	});

	const slackPreferencesQueryKey = getSlackUserPreferencesQueryKey({});
	const updateSlackPreferencesMutation = useMutation({
		...updateSlackUserPreferencesMutation(),
		onSuccess: (updatedWorkspace) => {
			queryClient.setQueryData<SlackUserPreferences>(slackPreferencesQueryKey, (current) => {
				const workspaces = current?.workspaces ?? [];
				const hasWorkspace = workspaces.some(
					(workspace) => workspace.workspaceSlug === updatedWorkspace.workspaceSlug,
				);
				return {
					workspaces: hasWorkspace
						? workspaces.map((workspace) =>
								workspace.workspaceSlug === updatedWorkspace.workspaceSlug
									? updatedWorkspace
									: workspace,
							)
						: [...workspaces, updatedWorkspace],
				};
			});
			void queryClient.invalidateQueries({ queryKey: slackPreferencesQueryKey });
			toast.success(
				updatedWorkspace.channelMessagesAllowed
					? "Slack channel-message use is on."
					: "Slack channel-message use is off.",
			);
		},
		onError: (error: DefaultError) => {
			toast.error(problemDetailOf(error, "Couldn't update Slack preferences. Please try again."));
		},
	});

	const linkedAccountsProps: LinkedAccountsSectionProps = {
		identities: linkedIdentitiesQuery.data ?? [],
		providers: identityProvidersQuery.data ?? [],
		onLink: (registrationId) => linkAccount(registrationId, "/settings"),
		// Guard against a double-submit: the trigger uses aria-disabled (kept focusable for the
		// busy announcement), which does not block clicks, so a mid-flight re-confirm would fire a
		// second DELETE against the already-removed row.
		onUnlink: (id) => {
			if (!unlinkMutation.isPending) {
				unlinkMutation.mutate({ path: { id } });
			}
		},
		unlinkingId: unlinkMutation.isPending ? unlinkMutation.variables.path.id : null,
		isLoading: linkedIdentitiesQuery.isLoading || identityProvidersQuery.isLoading,
		isError: linkedIdentitiesQuery.isError || identityProvidersQuery.isError,
		error: linkedIdentitiesQuery.error ?? identityProvidersQuery.error,
		onRetry: () => {
			void linkedIdentitiesQuery.refetch();
			void identityProvidersQuery.refetch();
		},
	};

	const slackProvider = identityProvidersQuery.data?.find(
		(provider) => provider.providerType?.toUpperCase() === "SLACK",
	);
	const slackIdentity = linkedIdentitiesQuery.data?.find(
		(identity) => identity.providerType?.toUpperCase() === "SLACK",
	);
	const slackAvailable = hasText(slackProvider?.registrationId) || slackIdentity !== undefined;

	const slackPreferencesQuery = useQuery({
		...getSlackUserPreferencesOptions({}),
		enabled: slackAvailable,
		retry: 1,
	});

	const slackPreferencesProps: SlackPreferencesSectionProps = {
		workspaces: slackPreferencesQuery.data?.workspaces ?? [],
		isSlackLinked: Boolean(slackIdentity),
		canConnectSlack: Boolean(slackProvider?.registrationId),
		onConnectSlack: () => {
			if (slackProvider?.registrationId) {
				linkAccount(slackProvider.registrationId, "/settings");
			}
		},
		onToggleChannelMessages: (workspaceSlug, channelMessagesAllowed) => {
			updateSlackPreferencesMutation.mutate({
				path: { workspaceSlug },
				body: { channelMessagesAllowed },
			});
		},
		updatingWorkspaceSlug: updateSlackPreferencesMutation.isPending
			? updateSlackPreferencesMutation.variables.path.workspaceSlug
			: null,
		isLoading:
			linkedIdentitiesQuery.isLoading ||
			identityProvidersQuery.isLoading ||
			(slackAvailable && slackPreferencesQuery.isLoading),
		isError: slackAvailable && slackPreferencesQuery.isError,
		error: slackPreferencesQuery.error,
		onRetry: () => void slackPreferencesQuery.refetch(),
	};

	return (
		<SettingsPage
			isLoading={isLoading}
			settingsError={settingsError}
			onRetrySettings={() => void refetchSettings()}
			practiceFeedbackProps={{
				practiceFeedbackDeliveryEnabled: settings?.practiceFeedbackDeliveryEnabled ?? true,
				onTogglePracticeFeedback: handlePracticeFeedbackToggle,
				isLoading: updateSettingsMutation.isPending,
			}}
			// No configured organisation means no study on this deployment, and a switch for a study
			// nobody runs is a promise the instance cannot keep.
			// Setup owns the question until it is answered for the organisation currently named; a switch
			// before that would stand in for a consent this account has not given.
			showResearchSection={
				accountConsent?.researchOrganization !== undefined && accountConsent.completed
			}
			researchProps={{
				organization: accountConsent?.researchOrganization ?? "",
				participateInResearch: accountConsent?.participateInResearch ?? false,
				onToggleResearch: handleResearchToggle,
				isLoading: consentQuery.isLoading || researchConsentMutation.isPending,
				isError: consentQuery.isError,
				error: consentQuery.error,
				onRetry: () => void consentQuery.refetch(),
			}}
			linkedAccountsProps={linkedAccountsProps}
			showSlackPreferencesSection={slackAvailable}
			slackPreferencesProps={slackPreferencesProps}
			onAccountDeleted={handleAccountDeleted}
		/>
	);
}
