import { useQuery, type UseQueryResult } from "@tanstack/react-query";

import {
	getConnectionSyncStatusOptions,
	getFeedbackModeRollupOptions,
	getIntegrationCatalogOptions,
	getMemberOnboardingSettingsOptions,
	getWorkspaceOptions,
	listAgentsOptions,
	listPracticesOptions,
	listReviewsOptions,
} from "@/api/@tanstack/react-query.gen";
import type { Workspace } from "@/api/types.gen";
import {
	memberTiers,
	unservedReviewTiers,
} from "@/components/admin/practices/review/review-readiness";
import {
	type FirstReviewFact,
	type PracticeReviewsFact,
	type RepositoriesFact,
	repositoriesFact,
	type ReviewModelFact,
	setupUnreadable,
	type WorkspaceSetupFacts,
} from "@/components/admin/settings/workspace-setup";
import type { WorkspaceSetupSummaryState } from "@/components/admin/settings/WorkspaceSetupSummary";

/** A read that is in, or failed: either way its row can say something. A read never enabled is pending. */
function settled(query: UseQueryResult<unknown, unknown>): boolean {
	return query.isSuccess || query.isError;
}

/**
 * The data a fact may read: none once the query's last read failed. TanStack Query keeps the last
 * successful data through a failed refresh, and a fact built on it would claim what the server no
 * longer confirms.
 */
function fresh<TData>(query: UseQueryResult<TData, unknown>): TData | undefined {
	return query.isError ? undefined : query.data;
}

/** While the first sync is waiting or running, the summary reads the sync status again this often. */
export const SETUP_SYNC_POLL_MS = 15_000;

function inFlight(fact: RepositoriesFact): boolean {
	return fact.status === "waiting" || fact.status === "syncing";
}

function practiceReviewsFact(workspace: Workspace | undefined): PracticeReviewsFact {
	if (workspace === undefined) {
		return { status: "unknown" };
	}
	return workspace.practicesEnabled
		? {
				status: "on",
				automatic: workspace.practiceReviewAutoTriggerEnabled,
				onRequest: workspace.practiceReviewManualTriggerEnabled,
			}
		: { status: "off" };
}

/**
 * The facts of the setup summary, composed from the reads the admin pages already make. A read that
 * fails leaves its row unknown, never ready. For the admin Settings page only: a page that members
 * also open must not make admin reads, since one refused for a missing passkey confirmation sends
 * the reader away to confirm it.
 */
export function useWorkspaceSetup(
	workspaceSlug: string,
	{ enabled = true }: { enabled?: boolean } = {},
): WorkspaceSetupSummaryState {
	const path = { workspaceSlug };
	const workspaceQuery = useQuery({ ...getWorkspaceOptions({ path }), enabled });
	const catalogQuery = useQuery({ ...getIntegrationCatalogOptions({ path }), enabled });
	const workspace = fresh(workspaceQuery);
	const catalog = fresh(catalogQuery);
	// The source-control connection: GitLab for a GitLab workspace, GitHub for every other.
	const scmKind = workspace && (workspace.kind === "GITLAB" ? "GITLAB" : "GITHUB");
	const entry = catalog?.find((candidate) => candidate.kind === scmKind);
	const connectionId = entry?.connected === true ? entry.connectionId : undefined;
	// The sync stream lives under Integrations only, so these pages poll while the first sync is
	// waiting or running, and stop once the fact settles.
	const statusQuery = useQuery({
		...getConnectionSyncStatusOptions({
			path: { workspaceSlug, connectionId: connectionId ?? -1 },
		}),
		enabled: enabled && connectionId !== undefined,
		refetchInterval: (query) =>
			query.state.data !== undefined &&
			inFlight(repositoriesFact(catalog, scmKind, query.state.data))
				? SETUP_SYNC_POLL_MS
				: false,
	});
	const practicesQuery = useQuery({ ...listPracticesOptions({ path }), enabled });
	const bindingsQuery = useQuery({ ...listAgentsOptions({ path }), enabled });
	const onboardingQuery = useQuery({ ...getMemberOnboardingSettingsOptions({ path }), enabled });
	const rollupQuery = useQuery({ ...getFeedbackModeRollupOptions({ path }), enabled });
	const finishedReviewsQuery = useQuery({
		...listReviewsOptions({
			path,
			query: { size: 1, kind: "ITEM", status: ["COMPLETE", "PARTIAL"] },
		}),
		enabled,
	});

	const queries: UseQueryResult<unknown, unknown>[] = [
		workspaceQuery,
		catalogQuery,
		...(connectionId === undefined ? [] : [statusQuery]),
		practicesQuery,
		bindingsQuery,
		onboardingQuery,
		rollupQuery,
		finishedReviewsQuery,
	];
	const retry = () => {
		for (const query of queries) {
			if (query.isError) {
				void query.refetch();
			}
		}
	};
	if (!enabled || !queries.every(settled)) {
		return { status: "loading" };
	}

	const bindings = fresh(bindingsQuery);
	const onboarding = fresh(onboardingQuery);
	const reviewModel: ReviewModelFact =
		bindings === undefined || onboarding === undefined
			? { status: "unknown" }
			: {
					status: "known",
					unserved: unservedReviewTiers(bindings, onboarding.aiChoiceRequired),
					memberTiers: memberTiers(onboarding.aiChoiceRequired).length,
				};
	const finished = fresh(finishedReviewsQuery)?.totalElements;
	let firstReview: FirstReviewFact = { status: "unknown" };
	if (finished !== undefined) {
		firstReview = finished > 0 ? { status: "done" } : { status: "none" };
	}
	const practices = fresh(practicesQuery);
	const rollup = fresh(rollupQuery);
	const facts: WorkspaceSetupFacts = {
		repositories: repositoriesFact(catalog, scmKind, fresh(statusQuery)),
		practiceReviews: practiceReviewsFact(workspace),
		practices:
			practices === undefined
				? { status: "unknown" }
				: { status: "known", chosen: practices.length },
		reviewModel,
		feedbackMode: rollup === undefined ? { status: "unknown" } : { status: "known", ...rollup },
		firstReview,
	};
	if (setupUnreadable(facts)) {
		const failed = queries.find((query) => query.isError);
		return { status: "error", error: failed?.error, onRetry: retry };
	}
	return { status: "ready", facts };
}
