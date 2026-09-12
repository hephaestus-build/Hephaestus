import type { PracticeGroupTrend, UserPracticeSummary } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import {
	PracticeGroupDetailPage,
	type ReviewRunFeedState,
} from "@/components/profile/PracticeGroupDetailPage";
import { PracticeGroupStandingCard } from "@/components/profile/PracticeGroupStandingCard";
import type { ObservationDetailState } from "@/components/profile/review-runs";
import { contributingPractices } from "@/lib/practice-standing";

import { UserViewErrorAlert } from "./UserViewErrorAlert";

export type UserPracticeViewState = PanelState<{ summary: UserPracticeSummary }>;

export interface UserViewGroupSelection {
	groupSlug: string;
	practiceSlug?: string;
	observationId?: string;
}

export type UserViewGroupState = PanelState<{
	trend: PracticeGroupTrend;
	feed: ReviewRunFeedState;
	observationDetail?: ObservationDetailState;
}>;

export type UserViewPracticesView =
	| { kind: "overview" }
	| { kind: "group"; selection: UserViewGroupSelection; group: UserViewGroupState };

export interface UserViewPracticesProps {
	practices: UserPracticeViewState;
	view: UserViewPracticesView;
	skeletonRows?: number;
	onOpenGroup: (groupSlug: string) => void;
	onSelectPractice: (practiceSlug: string | undefined) => void;
	onToggleObservation: (observationId: string) => void;
	onBack: () => void;
}

export function UserViewPractices({
	practices,
	view,
	skeletonRows,
	onOpenGroup,
	onSelectPractice,
	onToggleObservation,
	onBack,
}: UserViewPracticesProps) {
	if (practices.status === "error") {
		return <UserViewErrorAlert error={practices.error} onRetry={practices.onRetry} />;
	}
	const summary = practices.status === "ready" ? practices.summary : undefined;
	if (view.kind === "overview") {
		return (
			<PracticeGroupStandingCard
				groups={summary?.groups ?? []}
				standings={Object.fromEntries(
					(summary?.groupStandings ?? []).map((standing) => [standing.groupSlug, standing]),
				)}
				practicesByGroup={Object.fromEntries(
					(summary?.groups ?? []).map((candidate) => [
						candidate.slug,
						summary?.standings.filter((standing) => standing.groupSlug === candidate.slug),
					]),
				)}
				isLoading={summary === undefined}
				onOpenDetails={(candidate) => onOpenGroup(candidate.slug)}
			/>
		);
	}
	const { selection, group } = view;
	if (group.status === "error") {
		return <UserViewErrorAlert error={group.error} onRetry={group.onRetry} />;
	}
	const ready = group.status === "ready" ? group : undefined;
	const { groupSlug } = selection;
	return (
		<PracticeGroupDetailPage
			group={summary?.groups.find((candidate) => candidate.slug === groupSlug)}
			standing={summary?.groupStandings.find((candidate) => candidate.groupSlug === groupSlug)}
			practices={
				summary &&
				contributingPractices(groupSlug, summary.practices, summary.standings, ready?.trend)
			}
			groupTrend={ready?.trend.group}
			selectedPracticeSlug={selection.practiceSlug}
			onSelectPractice={onSelectPractice}
			feed={ready?.feed ?? { status: "loading" }}
			skeletonRows={skeletonRows}
			openObservationId={selection.observationId}
			observationDetail={ready?.observationDetail}
			onToggleObservation={onToggleObservation}
			isLoading={summary === undefined || ready === undefined}
			onBack={onBack}
		/>
	);
}
