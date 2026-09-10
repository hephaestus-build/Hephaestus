import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { InfoIcon } from "lucide-react";
import { toast } from "sonner";
import {
	autonomyRollupOptions,
	getPracticeReviewSettingsOptions,
	getRepositoriesToMonitorOptions,
	getWorkspaceOptions,
	listAgentsOptions,
	listBackfillRunsOptions,
	listBackfillRunsQueryKey,
	listWorkspaceContributorsOptions,
	listPracticesOptions,
	listSweepSchedulesOptions,
	preflightBackfillRunMutation,
	previewCoverageMutation,
	updateBackfillRunStatusMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	CreateReviewBackfillRunRequest,
	UpdatePracticeReviewSettingsRequest,
} from "@/api/types.gen";
import { PracticeAutonomyPage } from "@/components/admin/practices/practice-autonomy/PracticeAutonomyPage";
import { PracticeReviewBackfill } from "@/components/admin/practices/PracticeReviewBackfill";
import {
	type PracticeReviewField,
	PracticeReviewSettings,
	type PracticeReviewWorkspaceUpdate,
} from "@/components/admin/practices/PracticeReviewSettings";
import { PracticeReviewSweepSchedule } from "@/components/admin/practices/PracticeReviewSweepSchedule";
import {
	PracticeDefinitionSkeleton,
	ReviewSettingsSkeleton,
} from "@/components/admin/practices/PracticeSkeletons";
import type {
	ReviewModelState,
	ReviewRunningState,
} from "@/components/admin/practices/review/review-readiness";
import {
	DEFAULT_REVIEW_SECTION,
	reviewSearchSchema,
} from "@/components/admin/practices/review/review-sections";
import { ReviewPage } from "@/components/admin/practices/review/ReviewPage";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import environment from "@/environment";
import { usePracticeAutonomyMutations } from "@/hooks/use-practice-autonomy-mutations";
import { usePracticeReviewSettingsMutation } from "@/hooks/use-practice-review-settings";
import { useSweepScheduleMutations } from "@/hooks/use-sweep-schedule-mutations";
import { useUpdateWorkspaceFeatures } from "@/hooks/use-update-workspace-features";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { useSearchState } from "@/lib/search-params";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/review")({
	head: workspaceAdminHead("Review"),
	validateSearch: reviewSearchSchema,
	component: ReviewRoute,
});

function ReviewRoute() {
	const { workspaceSlug } = Route.useParams();
	const { section, overrides } = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const setSearch = useSearchState();

	const workspaceQuery = useQuery({ ...getWorkspaceOptions({ path: { workspaceSlug } }) });
	const bindingsQuery = useQuery({ ...listAgentsOptions({ path: { workspaceSlug } }) });
	const reviewModel: ReviewModelState = bindingsQuery.isPending
		? { status: "loading" }
		: bindingsQuery.isError
			? { status: "error" }
			: {
					status: "ready",
					binding: bindingsQuery.data.find((agent) => agent.purpose === "PRACTICE_REVIEW"),
				};

	const running: ReviewRunningState | undefined = workspaceQuery.data && {
		enabled: workspaceQuery.data.practicesEnabled,
		model: reviewModel,
	};

	return (
		<ReviewPage
			section={section ?? DEFAULT_REVIEW_SECTION}
			onSectionChange={(next) =>
				void navigate({
					search: (previous) => ({
						...previous,
						section: next === DEFAULT_REVIEW_SECTION ? undefined : next,
					}),
				})
			}
			running={running}
			sections={{
				"how-much": (
					<HowMuchSection
						workspaceSlug={workspaceSlug}
						overridesOnly={overrides === true}
						onOverridesOnlyChange={(next) =>
							void setSearch((previous) => ({
								...previous,
								overrides: next ? true : undefined,
							}))
						}
					/>
				),
				"when-and-where": <WhenAndWhereSection workspaceSlug={workspaceSlug} />,
				"past-work": <PastWorkSection workspaceSlug={workspaceSlug} />,
			}}
		/>
	);
}

interface HowMuchSectionProps {
	workspaceSlug: string;
	overridesOnly: boolean;
	onOverridesOnlyChange: (next: boolean) => void;
}

function HowMuchSection({
	workspaceSlug,
	overridesOnly,
	onOverridesOnlyChange,
}: HowMuchSectionProps) {
	const settingsQuery = useQuery({
		...getPracticeReviewSettingsOptions({ path: { workspaceSlug } }),
	});
	const rollupQuery = useQuery({ ...autonomyRollupOptions({ path: { workspaceSlug } }) });
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });

	const autonomyMutations = usePracticeAutonomyMutations(workspaceSlug);
	const updateSettings = usePracticeReviewSettingsMutation(workspaceSlug, {
		success: "Review settings updated",
		error: "Failed to update review settings",
	});

	if (settingsQuery.isPending || rollupQuery.isPending || practicesQuery.isPending) {
		return <PracticeDefinitionSkeleton />;
	}

	const error = settingsQuery.error ?? rollupQuery.error ?? practicesQuery.error;
	if (error || !settingsQuery.data || !rollupQuery.data || !practicesQuery.data) {
		return (
			<QueryErrorAlert
				error={error}
				title="Couldn't load the autonomy settings"
				onRetry={() => {
					void settingsQuery.refetch();
					void rollupQuery.refetch();
					void practicesQuery.refetch();
				}}
			/>
		);
	}

	return (
		<PracticeAutonomyPage
			workspaceSlug={workspaceSlug}
			settings={settingsQuery.data}
			rollup={rollupQuery.data}
			practices={practicesQuery.data}
			pending={{
				workspace: updateSettings.isPending,
				groupSlugs: autonomyMutations.pendingGroupSlugs,
				practiceSlugs: autonomyMutations.pendingPracticeSlugs,
				bulk: autonomyMutations.bulk,
			}}
			overridesOnly={overridesOnly}
			onOverridesOnlyChange={onOverridesOnlyChange}
			onSetWorkspaceDefault={(defaultAutonomy) =>
				updateSettings.mutate({ path: { workspaceSlug }, body: { defaultAutonomy } })
			}
			onClearWorkspaceDefault={() =>
				updateSettings.mutate({
					path: { workspaceSlug },
					body: { reset: ["DEFAULT_AUTONOMY"] },
				})
			}
			onSetGroupAutonomy={(groupSlug, autonomy) =>
				autonomyMutations.setGroupAutonomy.mutate({
					path: { workspaceSlug, groupSlug },
					body: { autonomy },
				})
			}
			onClearGroupAutonomy={(groupSlug) =>
				autonomyMutations.setGroupAutonomy.mutate({ path: { workspaceSlug, groupSlug }, body: {} })
			}
			onSetPracticeAutonomy={(practiceSlug, autonomy) =>
				autonomyMutations.setPracticeAutonomy.mutate({
					path: { workspaceSlug, practiceSlug },
					body: { autonomy },
				})
			}
			onClearPracticeAutonomy={(practiceSlug) =>
				autonomyMutations.setPracticeAutonomy.mutate({
					path: { workspaceSlug, practiceSlug },
					body: {},
				})
			}
			onBulkSetAutonomy={(practiceSlugs, autonomy) => {
				void autonomyMutations.setManyPracticeAutonomies(practiceSlugs, autonomy);
			}}
		/>
	);
}

function WhenAndWhereSection({ workspaceSlug }: { workspaceSlug: string }) {
	const reviewSettingsQuery = useQuery({
		...getPracticeReviewSettingsOptions({ path: { workspaceSlug } }),
	});
	const bindingsQuery = useQuery({ ...listAgentsOptions({ path: { workspaceSlug } }) });
	const settingsReviewModel: ReviewModelState = bindingsQuery.isPending
		? { status: "loading" }
		: bindingsQuery.isError
			? { status: "error" }
			: {
					status: "ready",
					binding: bindingsQuery.data.find((agent) => agent.purpose === "PRACTICE_REVIEW"),
				};
	const workspaceQuery = useQuery({ ...getWorkspaceOptions({ path: { workspaceSlug } }) });
	const schedulesQuery = useQuery(listSweepSchedulesOptions({ path: { workspaceSlug } }));
	const repositoriesQuery = useQuery(getRepositoriesToMonitorOptions({ path: { workspaceSlug } }));
	const membersQuery = useQuery(listWorkspaceContributorsOptions({ path: { workspaceSlug } }));
	const coveragePreview = useMutation(previewCoverageMutation());

	const updatePracticeReviewSettings = usePracticeReviewSettingsMutation(workspaceSlug, {
		success: "Review settings updated",
		error: "Failed to update review settings",
	});
	const updateFeatures = useUpdateWorkspaceFeatures(workspaceSlug, {
		success: "Practice review settings updated",
		error: "Failed to update practice review settings",
	});
	const schedules = useSweepScheduleMutations(workspaceSlug);

	const isLoading = reviewSettingsQuery.isPending || workspaceQuery.isPending;
	const error = reviewSettingsQuery.error ?? workspaceQuery.error;

	return (
		<div className="max-w-3xl space-y-8">
			{environment.deployment.environment === "preview" && (
				<Alert>
					<InfoIcon aria-hidden />
					<AlertTitle>This preview starts in silence mode</AlertTitle>
					<AlertDescription>
						Staging data is available, but cloned model bindings, triggers, and recurring checks
						start paused. To test a review in this preview only, select and enable the practice
						review model below, then enable the manual or automatic trigger you need.
					</AlertDescription>
				</Alert>
			)}
			{isLoading ? (
				<ReviewSettingsSkeleton />
			) : error || !reviewSettingsQuery.data || !workspaceQuery.data ? (
				<QueryErrorAlert
					error={error}
					title="Couldn't load the review settings"
					onRetry={() => {
						void reviewSettingsQuery.refetch();
						void workspaceQuery.refetch();
					}}
				/>
			) : (
				<PracticeReviewSettings
					workspaceSlug={workspaceSlug}
					model={
						settingsReviewModel.status === "error"
							? { ...settingsReviewModel, onRetry: () => void bindingsQuery.refetch() }
							: settingsReviewModel
					}
					workspace={{
						enabled: workspaceQuery.data.practicesEnabled,
						autoTriggerEnabled: workspaceQuery.data.practiceReviewAutoTriggerEnabled,
						manualTriggerEnabled: workspaceQuery.data.practiceReviewManualTriggerEnabled,
						isSaving: updateFeatures.isPending,
						onUpdate: (settings: PracticeReviewWorkspaceUpdate) =>
							updateFeatures.mutate({ path: { workspaceSlug }, body: settings }),
					}}
					policy={{
						settings: reviewSettingsQuery.data,
						isSaving: updatePracticeReviewSettings.isPending,
						onUpdate: async (
							settings: UpdatePracticeReviewSettingsRequest,
							sourceEtag?: string,
						) => {
							await updatePracticeReviewSettings.mutateAsync({
								path: { workspaceSlug },
								body: settings,
								headers: sourceEtag ? { "If-Match": sourceEtag } : undefined,
							});
						},
						onReset: (field: PracticeReviewField) =>
							updatePracticeReviewSettings.mutate({
								path: { workspaceSlug },
								body: { reset: [field] },
							}),
					}}
					coverage={{
						preview: (scope) =>
							coveragePreview.mutateAsync({ path: { workspaceSlug }, body: scope }),
						repositories: repositoriesQuery.isPending
							? { status: "loading" }
							: repositoriesQuery.isError
								? {
										status: "error",
										error: repositoriesQuery.error,
										onRetry: () => void repositoriesQuery.refetch(),
									}
								: {
										status: "ready",
										options: repositoriesQuery.data.map((repository) => ({
											value: repository,
											label: repository,
										})),
									},
						people: membersQuery.isPending
							? { status: "loading" }
							: membersQuery.isError
								? {
										status: "error",
										error: membersQuery.error,
										onRetry: () => void membersQuery.refetch(),
									}
								: {
										status: "ready",
										options: membersQuery.data.flatMap((member) =>
											member.userId == null || !member.eligibleForPracticeReview
												? []
												: [
														{
															value: member.userId,
															label:
																[member.userName, member.userLogin].find(
																	(name) => name != null && name.trim() !== "",
																) ?? `Member ${member.userId}`,
															description: member.userLogin ? `@${member.userLogin}` : undefined,
														},
													],
										),
									},
					}}
				/>
			)}
			<PracticeReviewSweepSchedule
				schedules={schedulesQuery.data ?? []}
				isLoading={schedulesQuery.isLoading}
				isError={schedulesQuery.isError}
				onRetry={() => void schedulesQuery.refetch()}
				isSaving={schedules.isSaving}
				onCreate={schedules.onCreate}
				onReplace={schedules.onReplace}
				onDelete={schedules.onDelete}
			/>
		</div>
	);
}

const ACTIVE_BACKFILL_POLL_MS = 15_000;

function PastWorkSection({ workspaceSlug }: { workspaceSlug: string }) {
	const queryClient = useQueryClient();

	const runsQuery = useQuery({
		...listBackfillRunsOptions({ path: { workspaceSlug } }),
		refetchInterval: (query) =>
			query.state.data?.some((run) => run.status === "RUNNING" || run.status === "PAUSED")
				? ACTIVE_BACKFILL_POLL_MS
				: false,
	});

	const invalidate = () =>
		queryClient.invalidateQueries({
			queryKey: listBackfillRunsQueryKey({ path: { workspaceSlug } }),
		});

	const preflight = useMutation({
		...preflightBackfillRunMutation(),
		onSuccess: () => {
			void invalidate();
		},
		onError: (error) => {
			toast.error("Couldn't estimate this backfill", { description: problemDetailOf(error) });
		},
	});

	const updateStatus = useMutation({
		...updateBackfillRunStatusMutation(),
		onSuccess: (run) => {
			void invalidate();
			if (run.status === "RUNNING") {
				toast.success("Backfill started");
			} else if (run.status === "CANCELLED") {
				toast.success("Backfill stopped");
			}
		},
		onError: (error) => {
			toast.error("Couldn't update this backfill", { description: problemDetailOf(error) });
		},
	});

	return (
		<div className="max-w-3xl">
			<PracticeReviewBackfill
				runs={runsQuery.data ?? []}
				isLoading={runsQuery.isLoading}
				isError={runsQuery.isError}
				onRetry={() => void runsQuery.refetch()}
				isEstimating={preflight.isPending}
				onEstimate={(request: CreateReviewBackfillRunRequest) =>
					preflight.mutate({ path: { workspaceSlug }, body: request })
				}
				isUpdating={updateStatus.isPending}
				onConfirm={(runId) =>
					updateStatus.mutate({ path: { workspaceSlug, runId }, body: { status: "RUNNING" } })
				}
				onCancel={(runId) =>
					updateStatus.mutate({ path: { workspaceSlug, runId }, body: { status: "CANCELLED" } })
				}
			/>
		</div>
	);
}
