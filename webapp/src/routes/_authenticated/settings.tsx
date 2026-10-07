import { type DefaultError, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { toast } from "sonner";
import { usePasskeySettings } from "@/hooks/use-passkey-settings";

import {
	getAccountAiChoiceOptions,
	getAccountAiChoiceQueryKey,
	getConsentStatusOptions,
	getConsentStatusQueryKey,
	getCurrentUserQueryKey,
	getNotificationPreferencesOptions,
	getNotificationPreferencesQueryKey,
	getSlackUserPreferencesOptions,
	getSlackUserPreferencesQueryKey,
	getUserSettingsOptions,
	getUserSettingsQueryKey,
	listIdentityProvidersOptions,
	listLinkedIdentitiesOptions,
	listLinkedIdentitiesQueryKey,
	listSessionsOptions,
	listSessionsQueryKey,
	revokeOtherSessionsMutation,
	revokeSessionMutation,
	unlinkIdentityMutation,
	updateAccountAiChoiceMutation,
	updateResearchConsentMutation,
	updateNotificationPreferencesMutation,
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
import { WORDING_VERSION } from "@/components/auth/consent-wording";
import type { EmailPreferencesSectionProps } from "@/components/settings/EmailPreferencesSection";
import type { LinkedAccountsSectionProps } from "@/components/settings/LinkedAccountsSection";
import { PasskeySettings } from "@/components/settings/PasskeySettings";
import type { SessionsSectionProps } from "@/components/settings/SessionsSection";
import { SettingsPage } from "@/components/settings/SettingsPage";
import type { SlackPreferencesSectionProps } from "@/components/settings/SlackPreferencesSection";
import { memberOnboardingQueryScope } from "@/hooks/use-member-onboarding";
import { productSurveyQueryScope } from "@/hooks/use-product-feedback";
import { pageHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

export const Route = createFileRoute("/_authenticated/settings")({
	head: pageHead("User settings"),
	component: RouteComponent,
});

function RouteComponent() {
	const passkeys = usePasskeySettings();
	const queryClient = useQueryClient();
	const { logout, linkAccount, isAppAdmin } = useAuth();
	const userSettingsQueryKey = getUserSettingsQueryKey();
	const consentQuery = useQuery(getConsentStatusOptions({}));
	const accountConsent = consentQuery.data;
	const navigate = useNavigate();

	// Setup can fall due again while this page is open — a renamed research organisation asks its
	// question afresh. The parent guard only runs on navigation, so without this the reader is left on
	// a page whose controls have quietly gone and whose writes the server has started refusing.
	useEffect(() => {
		if (accountConsent?.completed === false) {
			void navigate({ to: "/consent", search: { returnTo: "/settings" }, replace: true });
		}
	}, [accountConsent?.completed, navigate]);

	const {
		data: settings,
		isLoading,
		isError: settingsError,
		refetch: refetchSettings,
	} = useQuery({
		...getUserSettingsOptions({}),
		retry: 1,
	});

	const emailPreferencesQuery = useQuery(getNotificationPreferencesOptions());
	const emailPreferencesMutation = useMutation({
		...updateNotificationPreferencesMutation(),
		onMutate: async () => {
			await queryClient.cancelQueries({ queryKey: getNotificationPreferencesQueryKey() });
		},
		onSuccess: async (preferences) => {
			await queryClient.cancelQueries({ queryKey: getNotificationPreferencesQueryKey() });
			queryClient.setQueryData(getNotificationPreferencesQueryKey(), preferences);
		},
		onError: async (error) => {
			await queryClient.invalidateQueries({ queryKey: getNotificationPreferencesQueryKey() });
			toast.error(
				problemStatusOf(error) === 412
					? "Email choices changed elsewhere. Review the updated choices and try again."
					: problemDetailOf(error, "We could not update your email choices. Try again."),
			);
		},
	});
	const preferences = emailPreferencesQuery.data;
	let emailPreferencesState: EmailPreferencesSectionProps["state"] = { status: "loading" };
	if (emailPreferencesQuery.isError) {
		emailPreferencesState = {
			status: "error",
			error: emailPreferencesQuery.error,
			onRetry: () => {
				void emailPreferencesQuery.refetch();
			},
		};
	} else if (preferences !== undefined) {
		emailPreferencesState = {
			status: "ready",
			preferences,
			isPending: emailPreferencesMutation.isPending,
			onChange: (choices) => {
				if (emailPreferencesMutation.isPending) {
					return;
				}
				emailPreferencesMutation.mutate({
					headers: { "If-Match": preferences.etag },
					body: choices,
				});
			},
		};
	}
	const emailPreferencesProps: EmailPreferencesSectionProps = {
		researchAvailable: hasText(accountConsent?.researchOrganization),
		isAppAdmin,
		state: emailPreferencesState,
	};

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
			toast.error("We could not update your settings. Try again.");
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
		if (!current) {
			return;
		}
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
			// Research surveys are offered on the strength of this answer, so the menu must follow it.
			void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
		},
		onError: (error) => {
			// The refusal may be the notice moving on — a renamed research organisation, or setup owed
			// again. Re-read it so the control reflects what this instance is now asking.
			void queryClient.invalidateQueries({ queryKey: getConsentStatusQueryKey({}) });
			// A retry cannot succeed while this page still shows words the server has replaced.
			toast.error(
				problemStatusOf(error) === 409
					? "Hephaestus was updated while this page was open. Reload the page, then try again."
					: "We could not update your research participation. Try again.",
			);
		},
	});

	// Echo the wording and organisation this page rendered: a settings tab left open across a new
	// release or a change of research organisation would otherwise record a decision about words the
	// reader never saw. The server refuses the mismatch.
	const handleResearchToggle = (checked: boolean) =>
		researchConsentMutation.mutate({
			body: {
				granted: checked,
				noticeVersion: WORDING_VERSION,
				researchOrganization: accountConsent?.researchOrganization,
			},
		});

	const aiChoiceQuery = useQuery(getAccountAiChoiceOptions({}));
	const aiChoiceMutation = useMutation({
		...updateAccountAiChoiceMutation(),
		onSuccess: (data) => {
			queryClient.setQueryData(getAccountAiChoiceQueryKey({}), data);
			// Every workspace's setup page reads the same answer.
			void queryClient.invalidateQueries({ queryKey: memberOnboardingQueryScope() });
		},
		onError: () => {
			toast.error("We could not save your AI choice. Try again.");
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
			toast.success("Account disconnected");
		},
		onError: (error: DefaultError) => {
			toast.error(problemDetailOf(error, "We could not disconnect that account. Try again."));
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
				updatedWorkspace.channelMessagesAllowed === true
					? "Slack channel-message use is on."
					: "Slack channel-message use is off.",
			);
		},
		onError: (error: DefaultError) => {
			toast.error(problemDetailOf(error, "We could not update your Slack preferences. Try again."));
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
		(identity) => identity.providerType.toUpperCase() === "SLACK",
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
			if (hasText(slackProvider?.registrationId)) {
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
		onRetry: () => {
			void slackPreferencesQuery.refetch();
		},
	};

	const sessionsQuery = useQuery(listSessionsOptions({}));
	const invalidateSessions = async () =>
		queryClient.invalidateQueries({ queryKey: listSessionsQueryKey() });
	const revokeSession = useMutation({
		...revokeSessionMutation(),
		onSuccess: () => {
			void invalidateSessions();
			toast.success("Signed out of that session");
		},
		onError: () => {
			toast.error("We could not sign out that session. Try again.");
		},
	});
	const revokeOtherSessions = useMutation({
		...revokeOtherSessionsMutation(),
		onSuccess: () => {
			void invalidateSessions();
			toast.success("Signed out of all other sessions");
		},
		onError: () => {
			toast.error("We could not sign out your other sessions. Try again.");
		},
	});
	let sessionsState: SessionsSectionProps["state"] = { status: "loading" };
	if (sessionsQuery.isError) {
		sessionsState = {
			status: "error",
			error: sessionsQuery.error,
			onRetry: () => {
				void sessionsQuery.refetch();
			},
		};
	} else if (sessionsQuery.data !== undefined) {
		sessionsState = {
			status: "ready",
			sessions: sessionsQuery.data,
			revokingJti: revokeSession.isPending ? revokeSession.variables.path.jti : null,
			revokingOthers: revokeOtherSessions.isPending,
			onRevoke: (jti) => revokeSession.mutate({ path: { jti } }),
			onRevokeOthers: () => revokeOtherSessions.mutate({}),
		};
	}

	return (
		<SettingsPage
			passkeys={<PasskeySettings {...passkeys} />}
			emailPreferencesProps={emailPreferencesProps}
			isLoading={isLoading}
			settingsError={settingsError}
			onRetrySettings={() => {
				void refetchSettings();
			}}
			practiceFeedbackProps={{
				practiceFeedbackDeliveryEnabled: settings?.practiceFeedbackDeliveryEnabled ?? true,
				onTogglePracticeFeedback: handlePracticeFeedbackToggle,
				isLoading: updateSettingsMutation.isPending,
			}}
			// No configured organisation means no research on this deployment, and a switch for it
			// nobody runs is a promise the instance cannot keep.
			// Setup owns the question until it is answered for the organisation currently named; a switch
			// before that would stand in for a consent this account has not given. A failed read still
			// shows the section, because its Retry is the only way to find out whether there is one.
			showResearchSection={
				consentQuery.isError ||
				(accountConsent?.researchOrganization !== undefined && accountConsent.completed)
			}
			researchProps={{
				organization: accountConsent?.researchOrganization ?? "",
				participateInResearch: accountConsent?.participateInResearch ?? false,
				onToggleResearch: handleResearchToggle,
				isLoading: consentQuery.isLoading || researchConsentMutation.isPending,
				isError: consentQuery.isError,
				error: consentQuery.error,
				onRetry: () => {
					void consentQuery.refetch();
				},
			}}
			aiChoiceProps={{
				choice: aiChoiceQuery.data?.choice,
				onSave: (choice) => aiChoiceMutation.mutate({ body: { choice } }),
				isSaving: aiChoiceMutation.isPending,
				isLoading: aiChoiceQuery.isLoading,
				isError: aiChoiceQuery.isError,
				error: aiChoiceQuery.error,
				onRetry: () => {
					void aiChoiceQuery.refetch();
				},
			}}
			linkedAccountsProps={linkedAccountsProps}
			showSlackPreferencesSection={slackAvailable}
			slackPreferencesProps={slackPreferencesProps}
			sessionsProps={{ state: sessionsState }}
			onAccountDeleted={handleAccountDeleted}
		/>
	);
}
