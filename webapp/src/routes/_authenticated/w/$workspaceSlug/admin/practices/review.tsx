import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { toast } from "sonner";
import {
	createReviewBatchMutation,
	createReviewScheduleMutation,
	deleteReviewScheduleMutation,
	getFeedbackModeRollupOptions,
	getPracticeReviewSettingsOptions,
	getWorkspaceOptions,
	listAgentsOptions,
	listMembershipsOptions,
	listPracticeGroupsOptions,
	listPracticesOptions,
	listReviewBatchesOptions,
	listReviewBatchesQueryKey,
	listReviewItemsInfiniteOptions,
	listReviewSchedulesOptions,
	listReviewSchedulesQueryKey,
	previewReviewScopeMutation,
	setReviewScheduleEnabledMutation,
	updateReviewBatchStatusMutation,
	updateReviewScheduleMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	AccountRef,
	CreateReviewBatchRequest,
	ReviewSchedule,
	UpdatePracticeReviewSettingsRequest,
} from "@/api/types.gen";
import { PracticeFeedbackModePage } from "@/components/admin/practices/practice-feedback-mode/PracticeFeedbackModePage";
import { PracticeReviewBatch } from "@/components/admin/practices/PracticeReviewBatch";
import { PracticeReviewSchedule } from "@/components/admin/practices/PracticeReviewSchedule";
import type {
	PeopleScopeOptions,
	RepositoryScopeOptions,
} from "@/components/admin/practices/PracticeReviewScopeSettings";
import {
	type PracticeReviewField,
	PracticeReviewSettings,
	type PracticeReviewWorkspaceUpdate,
} from "@/components/admin/practices/PracticeReviewSettings";
import {
	PracticeTreeSkeleton,
	ReviewSettingsSkeleton,
} from "@/components/admin/practices/PracticeSkeletons";
import {
	DEFAULT_REVIEW_SECTION,
	reviewSearchSchema,
} from "@/components/admin/practices/review/review-sections";
import { ReviewPage } from "@/components/admin/practices/review/ReviewPage";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { usePracticeFeedbackModeMutations } from "@/hooks/use-practice-feedback-mode-mutations";
import { usePracticeReviewSettingsMutation } from "@/hooks/use-practice-review-settings";
import { reviewModelOf, useReviewReadiness } from "@/hooks/use-review-readiness";
import { useUpdateWorkspaceFeatures } from "@/hooks/use-update-workspace-features";
import { ITEM_KIND } from "@/lib/item-kinds";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { useSearchState } from "@/lib/search-params";
import { hasText } from "@/lib/text";
import { pagedListState } from "@/runtime/tanstack-query/infinite-list";
import { springPageParams } from "@/runtime/tanstack-query/spring-page";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/review")({
	head: workspaceAdminHead("Review settings"),
	validateSearch: reviewSearchSchema,
	component: ReviewRoute,
});

const PICKER_PAGE_SIZE = 100;

function ReviewRoute() {
	const { workspaceSlug } = Route.useParams();
	const { section, overrides } = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const setSearch = useSearchState();

	const readiness = useReviewReadiness(workspaceSlug);

	return (
		<ReviewPage
			section={section ?? DEFAULT_REVIEW_SECTION}
			onSectionChange={(next) => {
				void navigate({
					search: (previous) => ({
						...previous,
						section: next === DEFAULT_REVIEW_SECTION ? undefined : next,
					}),
				});
			}}
			readiness={readiness}
			sections={{
				"feedback-mode": (
					<FeedbackModeSection
						workspaceSlug={workspaceSlug}
						overridesOnly={overrides === true}
						onOverridesOnlyChange={(next) => {
							void setSearch((previous) => ({
								...previous,
								overrides: next ? true : undefined,
							}));
						}}
					/>
				),
				"automated-review": <AutomatedReviewSection workspaceSlug={workspaceSlug} />,
				"past-work": <PastWorkSection workspaceSlug={workspaceSlug} />,
			}}
		/>
	);
}

interface FeedbackModeSectionProps {
	workspaceSlug: string;
	overridesOnly: boolean;
	onOverridesOnlyChange: (next: boolean) => void;
}

function FeedbackModeSection({
	workspaceSlug,
	overridesOnly,
	onOverridesOnlyChange,
}: FeedbackModeSectionProps) {
	const settingsQuery = useQuery(getPracticeReviewSettingsOptions({ path: { workspaceSlug } }));
	const rollupQuery = useQuery(getFeedbackModeRollupOptions({ path: { workspaceSlug } }));
	const groupsQuery = useQuery(listPracticeGroupsOptions({ path: { workspaceSlug } }));
	const practicesQuery = useQuery(listPracticesOptions({ path: { workspaceSlug } }));

	const feedbackModes = usePracticeFeedbackModeMutations(workspaceSlug);
	const updateSettings = usePracticeReviewSettingsMutation(workspaceSlug, {
		success: "Review settings updated",
		error: "We could not update review settings",
	});

	const queries = [settingsQuery, rollupQuery, groupsQuery, practicesQuery];
	if (queries.some((query) => query.isPending)) {
		return <PracticeTreeSkeleton groups={3} practicesPerGroup={3} />;
	}

	const error = queries.find((query) => query.error)?.error;
	if (
		error ||
		!settingsQuery.data ||
		!rollupQuery.data ||
		!groupsQuery.data ||
		!practicesQuery.data
	) {
		return (
			<QueryErrorAlert
				error={error}
				title="We could not load the feedback modes"
				onRetry={() => {
					for (const query of queries) {
						void query.refetch();
					}
				}}
			/>
		);
	}

	return (
		<PracticeFeedbackModePage
			workspaceSlug={workspaceSlug}
			settings={settingsQuery.data}
			rollup={rollupQuery.data}
			groups={groupsQuery.data}
			practices={practicesQuery.data}
			pending={{
				workspace: updateSettings.isPending,
				groupSlugs: feedbackModes.pendingGroupSlugs,
				practiceSlugs: feedbackModes.pendingPracticeSlugs,
				bulk: feedbackModes.bulk,
			}}
			overridesOnly={overridesOnly}
			onOverridesOnlyChange={onOverridesOnlyChange}
			onSetWorkspaceDefault={(defaultFeedbackMode) =>
				updateSettings.mutate({ path: { workspaceSlug }, body: { defaultFeedbackMode } })
			}
			onClearWorkspaceDefault={() =>
				updateSettings.mutate({
					path: { workspaceSlug },
					body: { reset: ["DEFAULT_FEEDBACK_MODE"] },
				})
			}
			onSetGroupFeedbackMode={(groupSlug, feedbackMode) =>
				feedbackModes.setGroupFeedbackMode.mutate({
					path: { workspaceSlug, groupSlug },
					body: { feedbackMode },
				})
			}
			onClearGroupFeedbackMode={(groupSlug) =>
				feedbackModes.setGroupFeedbackMode.mutate({
					path: { workspaceSlug, groupSlug },
					body: { feedbackMode: null },
				})
			}
			onSetPracticeFeedbackMode={(practiceSlug, feedbackMode) =>
				feedbackModes.setPracticeFeedbackMode.mutate({
					path: { workspaceSlug, practiceSlug },
					body: { feedbackMode },
				})
			}
			onClearPracticeFeedbackMode={(practiceSlug) =>
				feedbackModes.setPracticeFeedbackMode.mutate({
					path: { workspaceSlug, practiceSlug },
					body: { feedbackMode: null },
				})
			}
			onBulkSetFeedbackMode={(practiceSlugs, feedbackMode) => {
				void feedbackModes.setManyPracticeFeedbackModes(practiceSlugs, feedbackMode);
			}}
		/>
	);
}

function accountLabel(account: AccountRef): string {
	return (
		[
			account.displayName,
			...account.identities.flatMap((identity) => [identity.displayName, identity.login]),
		].find((name): name is string => hasText(name)) ?? `Member ${account.id}`
	);
}

function accountDescription(account: AccountRef): string | undefined {
	const logins = account.identities.flatMap((identity) =>
		hasText(identity.login) ? [`@${identity.login}`] : [],
	);
	if (account.status === "UNCLAIMED") {
		logins.push("not signed in yet");
	}
	return logins.length === 0 ? undefined : logins.join(", ");
}

/** A schedule's version, as the strong entity tag the server compares an If-Match against. */
const scheduleTag = (schedule: ReviewSchedule) => `"${schedule.version}"`;

function AutomatedReviewSection({ workspaceSlug }: { workspaceSlug: string }) {
	const queryClient = useQueryClient();
	const reviewSettingsQuery = useQuery(
		getPracticeReviewSettingsOptions({ path: { workspaceSlug } }),
	);
	const bindingsQuery = useQuery(listAgentsOptions({ path: { workspaceSlug } }));
	const settingsReviewModel = reviewModelOf(bindingsQuery);
	const workspaceQuery = useQuery(getWorkspaceOptions({ path: { workspaceSlug } }));
	const schedulesQuery = useQuery(listReviewSchedulesOptions({ path: { workspaceSlug } }));
	const repositoriesOptions = listReviewItemsInfiniteOptions({
		path: { workspaceSlug },
		query: { itemKind: ITEM_KIND.repository, size: PICKER_PAGE_SIZE },
	});
	const repositoriesQuery = useInfiniteQuery({ ...repositoriesOptions, ...springPageParams });
	useQueryReadRefusal(
		repositoriesOptions.queryKey,
		repositoriesQuery.isError,
		repositoriesQuery.error,
	);
	const membersQuery = useQuery(
		listMembershipsOptions({ path: { workspaceSlug }, query: { size: PICKER_PAGE_SIZE } }),
	);
	const scopePreview = useMutation(previewReviewScopeMutation());

	const updatePracticeReviewSettings = usePracticeReviewSettingsMutation(workspaceSlug, {
		success: "Review settings updated",
		error: "We could not update review settings",
	});
	const updateFeatures = useUpdateWorkspaceFeatures(workspaceSlug, {
		success: "Practice review settings updated",
		error: "We could not update practice review settings",
	});

	// The server computes schedule timing; invalidate the list rather than predict the next run.
	const invalidateSchedules = async () =>
		queryClient.invalidateQueries({
			queryKey: listReviewSchedulesQueryKey({ path: { workspaceSlug } }),
		});
	const scheduleFailed = (verb: string) => (error: unknown) => {
		void invalidateSchedules();
		toast.error(`We could not ${verb} this recurring check`, {
			description: problemDetailOf(error),
		});
	};
	const createSchedule = useMutation({
		...createReviewScheduleMutation(),
		onSuccess: () => {
			void invalidateSchedules();
			toast.success("Recurring check started");
		},
		onError: scheduleFailed("start"),
	});
	const replaceSchedule = useMutation({
		...updateReviewScheduleMutation(),
		onSuccess: () => {
			void invalidateSchedules();
		},
		onError: scheduleFailed("update"),
	});
	const enableSchedule = useMutation({
		...setReviewScheduleEnabledMutation(),
		onSuccess: (schedule) => {
			void invalidateSchedules();
			toast.success(schedule.enabled ? "Recurring check resumed" : "Recurring check paused");
		},
		onError: scheduleFailed("change"),
	});
	const deleteSchedule = useMutation({
		...deleteReviewScheduleMutation(),
		onSuccess: () => {
			void invalidateSchedules();
			toast.success("Recurring check deleted");
		},
		onError: (error) => {
			if (problemStatusOf(error) === 409) {
				toast.error("This recurring check has already run", {
					description: "Its earlier runs keep it in place. Pause it instead.",
				});
				return;
			}
			scheduleFailed("delete")(error);
		},
	});

	const isLoading = reviewSettingsQuery.isPending || workspaceQuery.isPending;
	const error = reviewSettingsQuery.error ?? workspaceQuery.error;

	const repositoryRows = pagedListState(repositoriesQuery, (repository) => repository.id);
	let repositoryOptions: RepositoryScopeOptions;
	if (repositoryRows.status === "ready") {
		const { status, rows, total, ...more } = repositoryRows;
		repositoryOptions = {
			status,
			// A repository with no record behind it carries only the kind's word and no provider.
			options: rows.map((repository) => ({
				itemId: repository.id,
				name: repository.label,
				removedAt: repository.removedAt,
				nameKnown: repository.provider !== null,
			})),
			total,
			more,
		};
	} else {
		repositoryOptions = repositoryRows;
	}

	let peopleOptions: PeopleScopeOptions;
	if (membersQuery.isPending) {
		peopleOptions = { status: "loading" };
	} else if (membersQuery.isError) {
		peopleOptions = {
			status: "error",
			error: membersQuery.error,
			onRetry: () => {
				void membersQuery.refetch();
			},
		};
	} else {
		peopleOptions = {
			status: "ready",
			options: membersQuery.data.content
				.filter((member) => member.eligibleForPracticeReview)
				.map((member) => ({
					value: member.account.id,
					label: accountLabel(member.account),
					description: accountDescription(member.account),
				})),
		};
	}

	let reviewSettings: ReactNode;
	if (isLoading) {
		reviewSettings = <ReviewSettingsSkeleton />;
	} else if (error || !reviewSettingsQuery.data || !workspaceQuery.data) {
		reviewSettings = (
			<QueryErrorAlert
				error={error}
				title="We could not load the review settings"
				onRetry={() => {
					void reviewSettingsQuery.refetch();
					void workspaceQuery.refetch();
				}}
			/>
		);
	} else {
		reviewSettings = (
			<PracticeReviewSettings
				workspaceSlug={workspaceSlug}
				model={
					settingsReviewModel.status === "error"
						? {
								...settingsReviewModel,
								onRetry: () => {
									void bindingsQuery.refetch();
								},
							}
						: settingsReviewModel
				}
				workspace={{
					enabled: workspaceQuery.data.practicesEnabled,
					startsFromActivity: workspaceQuery.data.practiceReviewAutoTriggerEnabled,
					startsOnRequest: workspaceQuery.data.practiceReviewManualTriggerEnabled,
					isSaving: updateFeatures.isPending,
					onUpdate: (settings: PracticeReviewWorkspaceUpdate) =>
						updateFeatures.mutate({ path: { workspaceSlug }, body: settings }),
				}}
				policy={{
					settings: reviewSettingsQuery.data,
					isSaving: updatePracticeReviewSettings.isPending,
					onUpdate: async (settings: UpdatePracticeReviewSettingsRequest, sourceEtag?: string) => {
						await updatePracticeReviewSettings.mutateAsync({
							path: { workspaceSlug },
							body: settings,
							headers: hasText(sourceEtag) ? { "If-Match": sourceEtag } : undefined,
						});
					},
					onReset: (field: PracticeReviewField) =>
						updatePracticeReviewSettings.mutate({
							path: { workspaceSlug },
							body: { reset: [field] },
						}),
				}}
				scope={{
					preview: async (scope) =>
						scopePreview.mutateAsync({ path: { workspaceSlug }, body: scope }),
					repositories: repositoryOptions,
					people: peopleOptions,
				}}
			/>
		);
	}

	return (
		<div className="max-w-3xl space-y-8">
			{reviewSettings}
			<PracticeReviewSchedule
				workspaceSlug={workspaceSlug}
				schedules={schedulesQuery.data ?? []}
				isLoading={schedulesQuery.isLoading}
				isError={schedulesQuery.isError}
				onRetry={() => {
					void schedulesQuery.refetch();
				}}
				isSaving={
					createSchedule.isPending ||
					replaceSchedule.isPending ||
					enableSchedule.isPending ||
					deleteSchedule.isPending
				}
				onCreate={(input) => createSchedule.mutate({ path: { workspaceSlug }, body: input })}
				onReplace={(schedule, input) =>
					replaceSchedule.mutate({
						path: { workspaceSlug, scheduleId: schedule.id },
						body: input,
						headers: { "If-Match": scheduleTag(schedule) },
					})
				}
				onSetEnabled={(schedule, enabled) =>
					enableSchedule.mutate({
						path: { workspaceSlug, scheduleId: schedule.id },
						body: { enabled },
						headers: { "If-Match": scheduleTag(schedule) },
					})
				}
				onDelete={(schedule) =>
					deleteSchedule.mutate({
						path: { workspaceSlug, scheduleId: schedule.id },
						headers: { "If-Match": scheduleTag(schedule) },
					})
				}
			/>
		</div>
	);
}

const ACTIVE_BATCH_POLL_MS = 15_000;

function PastWorkSection({ workspaceSlug }: { workspaceSlug: string }) {
	const queryClient = useQueryClient();

	const batchesQuery = useQuery({
		...listReviewBatchesOptions({ path: { workspaceSlug } }),
		refetchInterval: (query) =>
			query.state.data?.content.some(
				(batch) => batch.status === "RUNNING" || batch.status === "PAUSED",
			) === true
				? ACTIVE_BATCH_POLL_MS
				: false,
	});

	const invalidate = async () =>
		queryClient.invalidateQueries({
			queryKey: listReviewBatchesQueryKey({ path: { workspaceSlug } }),
		});

	const estimate = useMutation({
		...createReviewBatchMutation(),
		onSuccess: () => {
			void invalidate();
		},
		onError: (error) => {
			toast.error("We could not estimate the cost of this review of past work", {
				description: problemDetailOf(error),
			});
		},
	});

	const updateStatus = useMutation({
		...updateReviewBatchStatusMutation(),
		onSuccess: (batch) => {
			void invalidate();
			if (batch.status === "RUNNING") {
				toast.success("Review of past work running");
			} else if (batch.status === "PAUSED") {
				toast.success("Review of past work paused");
			} else if (batch.status === "CANCELLED") {
				toast.success("Review of past work stopped");
			}
		},
		onError: (error) => {
			void invalidate();
			toast.error("We could not update this review of past work", {
				description: problemDetailOf(error),
			});
		},
	});

	return (
		<div className="max-w-3xl">
			<PracticeReviewBatch
				batches={batchesQuery.data?.content ?? []}
				isLoading={batchesQuery.isLoading}
				isError={batchesQuery.isError}
				onRetry={() => {
					void batchesQuery.refetch();
				}}
				isEstimating={estimate.isPending}
				onEstimate={(request: CreateReviewBatchRequest) =>
					estimate.mutate({ path: { workspaceSlug }, body: request })
				}
				isUpdating={updateStatus.isPending}
				onChangeStatus={(batch, status) =>
					updateStatus.mutate({
						path: { workspaceSlug, batchId: batch.id },
						body: { status },
						headers: { "If-Match": batch.etag },
					})
				}
			/>
		</div>
	);
}
