import {
	type DefaultError,
	type UseQueryResult,
	useMutation,
	useQuery,
	useQueryClient,
} from "@tanstack/react-query";
import {
	createFileRoute,
	retainSearchParams,
	useLocation,
	useNavigate,
} from "@tanstack/react-router";
import { useEffect } from "react";
import { usePasskeySettings } from "@/hooks/use-passkey-settings";
import { toast } from "@/lib/toast";
import type { Route as RootRoute } from "@/routes/__root";

import {
	getAccountAiChoiceOptions,
	getAccountAiChoiceQueryKey,
	getConsentStatusOptions,
	getConsentStatusQueryKey,
	getCurrentUserQueryKey,
	getNotificationPreferencesOptions,
	getProductFeedbackSharingOptions,
	getProductFeedbackSharingQueryKey,
	getPublicActivityChoiceOptions,
	getPublicActivityChoiceQueryKey,
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
	updatePublicActivityChoiceMutation,
	updateResearchConsentMutation,
	updateNotificationPreferencesMutation,
	updateProductFeedbackSharingMutation,
	updateSlackUserPreferencesMutation,
	updateUserSettingsMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Options } from "@/api/sdk.gen";
import type {
	NotificationPreferences,
	SlackUserPreferences,
	UpdateNotificationPreferences,
	UpdateUserSettings,
	UpdateUserSettingsData,
	UpdateUserSettingsResponse,
	UserSettings,
} from "@/api/types.gen";
import { WORDING_VERSION } from "@/components/auth/consent-wording";
import type { EmailPreferencesSectionProps } from "@/components/settings/EmailPreferencesSection";
import type { LinkedAccountsSectionProps } from "@/components/settings/LinkedAccountsSection";
import { PasskeySettings } from "@/components/settings/PasskeySettings";
import type { ProductFeedbackSharingSectionProps } from "@/components/settings/ProductFeedbackSharingSection";
import type { PublicActivitySectionProps } from "@/components/settings/PublicActivitySection";
import type { SessionsSectionProps } from "@/components/settings/SessionsSection";
import { SettingsPage } from "@/components/settings/SettingsPage";
import type { SlackPreferencesSectionProps } from "@/components/settings/SlackPreferencesSection";
import type { WeeklyDigestSectionProps } from "@/components/settings/WeeklyDigestSection";
import { memberOnboardingQueryScope } from "@/hooks/use-member-onboarding";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { browserTimeZone, TIME_ZONES } from "@/lib/dates";
import { pageHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { announcePublicActivityChoice } from "@/lib/public-activity-choice";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";
import { retryQuery } from "@/runtime/tanstack-query/query-defaults";
import { refreshPublicActivity } from "@/runtime/tanstack-query/refresh-public-activity";

/** Every Slack preference write, whichever workspace it changes. */
const SLACK_PREFERENCE_WRITE_KEY = ["updateSlackUserPreferences"];

function slackPreferenceWorkspaceOf(variables: unknown): string | undefined {
	return pathString(variables, "workspaceSlug");
}

export const Route = createFileRoute("/_authenticated/settings")({
	search: {
		middlewares: [retainSearchParams<typeof RootRoute.types.fullSearchSchema>(["chromeWorkspace"])],
	},
	head: pageHead("User settings"),
	component: RouteComponent,
});

function RouteComponent() {
	const passkeys = usePasskeySettings();
	const queryClient = useQueryClient();
	const { linkAccount, isAppAdmin, linkedProviders } = useAuth();
	const userSettingsQueryKey = getUserSettingsQueryKey();
	const consentQuery = useQuery(getConsentStatusOptions({}));
	const accountConsent = consentQuery.data;
	const navigate = useNavigate();
	const location = useLocation();

	// Setup can fall due again while this page is open — a renamed research organisation asks its
	// question afresh. The parent guard only runs on navigation, so without this the reader is left on
	// a page whose controls have quietly gone and whose writes the server has started refusing.
	useEffect(() => {
		if (accountConsent?.completed === false && location.pathname === Route.fullPath) {
			void navigate({ to: "/consent", search: { returnTo: location.href }, replace: true });
		}
	}, [accountConsent?.completed, location.href, location.pathname, navigate]);

	const {
		data: settings,
		isLoading,
		isError: settingsError,
		refetch: refetchSettings,
	} = useQuery({
		...getUserSettingsOptions({}),
		retry: (failureCount, error) => failureCount < 1 && retryQuery(failureCount, error),
	});

	const preferencesQuery = useQuery(getNotificationPreferencesOptions());
	const preferencesMutation = useMutation({
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
					? "Your choices changed elsewhere. Review the updated choices and try again."
					: problemDetailOf(error, "We could not update your choices. Try again."),
			);
		},
	});
	// The one writer of the notification preferences: the email choices and the weekly digest share
	// one ETag, so a second writer would send a stale one.
	const updatePreferences = (patch: Partial<UpdateNotificationPreferences>) => {
		const current = preferencesQuery.data;
		if (current === undefined || preferencesMutation.isPending) {
			return;
		}
		const { productFeedback, productFeedbackUpdates, weeklyDigest, workspaceAlerts } = current;
		preferencesMutation.mutate({
			headers: { "If-Match": current.etag },
			body: {
				productFeedback,
				productFeedbackUpdates,
				weeklyDigest,
				workspaceAlerts,
				...patch,
			},
		});
	};
	const preferences = preferencesQuery.data;
	let emailPreferencesState: EmailPreferencesSectionProps["state"] = { status: "loading" };
	if (preferencesQuery.isError) {
		emailPreferencesState = {
			status: "error",
			error: preferencesQuery.error,
			onRetry: () => {
				void preferencesQuery.refetch();
			},
		};
	} else if (preferences !== undefined) {
		emailPreferencesState = {
			status: "ready",
			preferences,
			isPending: preferencesMutation.isPending,
			onChange: updatePreferences,
		};
	}
	const emailPreferencesProps: EmailPreferencesSectionProps = {
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
			if (previousSettings) {
				queryClient.setQueryData<UserSettings>(userSettingsQueryKey, {
					...previousSettings,
					...variables.body,
				});
			}
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

	// Reads the latest cache to avoid a stale-closure race under rapid toggling. The time zone is sent
	// only when it changes, because the server keeps the saved one when the request has none.
	const updateSetting = (
		patch: Partial<UpdateUserSettings>,
		options?: { onSuccess: () => void },
	) => {
		const current = queryClient.getQueryData<UserSettings>(userSettingsQueryKey);
		if (!current) {
			return;
		}
		updateSettingsMutation.mutate(
			{
				body: {
					practiceFeedbackDeliveryEnabled: current.practiceFeedbackDeliveryEnabled,
					...patch,
				},
			},
			options,
		);
	};

	const handlePracticeFeedbackToggle = (checked: boolean) =>
		updateSetting({ practiceFeedbackDeliveryEnabled: checked });

	const weeklyDigestState = weeklyDigestStateOf(preferencesQuery, settings, {
		updatePreferences,
		updateSetting,
		pending: [preferencesMutation.isPending, updateSettingsMutation.isPending],
	});

	const researchConsentMutation = useMutation({
		...updateResearchConsentMutation(),
		onSuccess: (status) => {
			queryClient.setQueryData(getConsentStatusQueryKey({}), status);
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

	const publicActivityQuery = useQuery(getPublicActivityChoiceOptions({}));
	const publicActivityMutation = useMutation({
		...updatePublicActivityChoiceMutation(),
		onSuccess: (data) => {
			queryClient.setQueryData(getPublicActivityChoiceQueryKey({}), data);
			void refreshPublicActivity(queryClient);
			announcePublicActivityChoice(data.visible);
		},
		onError: () => {
			toast.error("We could not save your choice. Try again.");
		},
		onSettled: () => {
			void queryClient.invalidateQueries({ queryKey: getPublicActivityChoiceQueryKey({}) });
		},
	});

	let publicActivityState: PublicActivitySectionProps["state"] = { status: "loading" };
	if (publicActivityQuery.isError) {
		publicActivityState = {
			status: "error",
			error: publicActivityQuery.error,
			onRetry: () => {
				void publicActivityQuery.refetch();
			},
		};
	} else if (publicActivityQuery.data !== undefined) {
		publicActivityState = {
			status: "ready",
			visible: publicActivityQuery.data.visible,
			pending: publicActivityMutation.isPending,
			onVisibleChange: (visible) => publicActivityMutation.mutate({ body: { visible } }),
		};
	}

	const sharingQuery = useQuery(getProductFeedbackSharingOptions());
	const sharingMutation = useMutation({
		...updateProductFeedbackSharingMutation(),
		onSuccess: (data) => {
			queryClient.setQueryData(getProductFeedbackSharingQueryKey(), data);
		},
		onError: () => {
			toast.error("We could not save your choice. Try again.");
			void queryClient.invalidateQueries({ queryKey: getProductFeedbackSharingQueryKey() });
		},
	});
	let productFeedbackSharingState: ProductFeedbackSharingSectionProps["state"] = {
		status: "loading",
	};
	if (sharingQuery.isError) {
		productFeedbackSharingState = {
			status: "error",
			error: sharingQuery.error,
			onRetry: () => {
				void sharingQuery.refetch();
			},
		};
	} else if (sharingQuery.data !== undefined) {
		productFeedbackSharingState = {
			status: "ready",
			sharing: sharingQuery.data,
			pending: sharingMutation.isPending,
			onChange: (body) => sharingMutation.mutate({ body }),
		};
	}

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
		...filedUnder(SLACK_PREFERENCE_WRITE_KEY, updateSlackUserPreferencesMutation()),
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
	const updatingSlackWorkspaceSlugs = usePendingMutationIds(
		SLACK_PREFERENCE_WRITE_KEY,
		slackPreferenceWorkspaceOf,
	);

	const linkedAccountsProps: LinkedAccountsSectionProps = {
		identities: linkedIdentitiesQuery.data ?? [],
		providers: identityProvidersQuery.data ?? [],
		linkedProviders,
		onLink: (registrationId) => linkAccount(registrationId, location.href),
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
		retry: (failureCount, error) => failureCount < 1 && retryQuery(failureCount, error),
	});

	const slackPreferencesProps: SlackPreferencesSectionProps = {
		workspaces: slackPreferencesQuery.data?.workspaces ?? [],
		isSlackLinked: Boolean(slackIdentity),
		canConnectSlack: Boolean(slackProvider?.registrationId),
		onConnectSlack: () => {
			if (hasText(slackProvider?.registrationId)) {
				linkAccount(slackProvider.registrationId, location.href);
			}
		},
		// One write per workspace at a time; another workspace's write never holds this one up.
		onToggleChannelMessages: (workspaceSlug, channelMessagesAllowed) => {
			const writing = queryClient.isMutating({
				mutationKey: SLACK_PREFERENCE_WRITE_KEY,
				predicate: ({ state }) => slackPreferenceWorkspaceOf(state.variables) === workspaceSlug,
			});
			if (writing === 0) {
				updateSlackPreferencesMutation.mutate({
					path: { workspaceSlug },
					body: { channelMessagesAllowed },
				});
			}
		},
		updatingWorkspaceSlugs: updatingSlackWorkspaceSlugs,
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
			weeklyDigestProps={{ state: weeklyDigestState }}
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
				choice: aiChoiceQuery.data?.choice ?? undefined,
				onSave: (choice) => aiChoiceMutation.mutate({ body: { choice } }),
				isSaving: aiChoiceMutation.isPending,
				isLoading: aiChoiceQuery.isLoading,
				isError: aiChoiceQuery.isError,
				error: aiChoiceQuery.error,
				onRetry: () => {
					void aiChoiceQuery.refetch();
				},
			}}
			publicActivityProps={{ state: publicActivityState }}
			productFeedbackSharingProps={{ state: productFeedbackSharingState }}
			linkedAccountsProps={linkedAccountsProps}
			showSlackPreferencesSection={slackAvailable}
			slackPreferencesProps={slackPreferencesProps}
			sessionsProps={{ state: sessionsState }}
			onAccountDeleted={openSignInAfterDeletion}
		/>
	);
}

/**
 * After deletion: a full reload to the sign-in page, which shows the notice that the Danger zone
 * recorded. No sign-out request: the deletion already revoked every session of the account.
 */
function openSignInAfterDeletion() {
	window.location.assign("/login");
}

/**
 * The weekly digest choice: one more subscription on the notification preferences, and the
 * account's time zone, which decides when the digest arrives.
 */
function weeklyDigestStateOf(
	preferencesQuery: UseQueryResult<NotificationPreferences>,
	settings: UserSettings | undefined,
	writes: {
		updatePreferences: (patch: Partial<UpdateNotificationPreferences>) => void;
		updateSetting: (
			patch: Partial<UpdateUserSettings>,
			options?: { onSuccess: () => void },
		) => void;
		/** Each write the choice makes: the preferences and the account's time zone. */
		pending: readonly boolean[];
	},
): WeeklyDigestSectionProps["state"] {
	const preferences = preferencesQuery.data;
	if (preferencesQuery.isError) {
		return {
			status: "error",
			error: preferencesQuery.error,
			onRetry: () => {
				void preferencesQuery.refetch();
			},
		};
	}
	if (preferences === undefined || settings === undefined) {
		return { status: "loading" };
	}
	return {
		status: "ready",
		enabled: preferences.weeklyDigest,
		available: preferences.weeklyDigestAvailable,
		...(hasText(settings.timeZone) ? { timeZone: settings.timeZone } : {}),
		zones: TIME_ZONES,
		isPending: writes.pending.includes(true),
		onToggle: (enabled) => {
			// The digest arrives by the reader's zone, so turning it on first saves the browser's when
			// the account has none. A zone the server refuses leaves the digest off.
			if (enabled && !hasText(settings.timeZone)) {
				writes.updateSetting(
					{ timeZone: browserTimeZone() },
					{ onSuccess: () => writes.updatePreferences({ weeklyDigest: true }) },
				);
				return;
			}
			writes.updatePreferences({ weeklyDigest: enabled });
		},
		onChangeTimeZone: (timeZone) => writes.updateSetting({ timeZone }),
	};
}
