import { CodeReviewIcon } from "@primer/octicons-react";
import { ArrowRightIcon } from "lucide-react";

import type { ProfileReviewActivity } from "@/api/types.gen";
import { EmptyState } from "@/components/shared/EmptyState";
import { Button } from "@/components/ui/button";
import type { ProviderType } from "@/lib/provider";

import { ReviewActivityCard } from "./ReviewActivityCard";

export interface ReviewActivitySectionProps {
	providerType: ProviderType;
	reviewActivity: readonly ProfileReviewActivity[];
	isLoading: boolean;
	personLabel: string;
	currUserIsDashboardUser: boolean;
	canViewAll: boolean;
	onViewAll: () => void;
}

export function ReviewActivitySection({
	providerType,
	reviewActivity,
	isLoading,
	personLabel,
	currUserIsDashboardUser,
	canViewAll,
	onViewAll,
}: ReviewActivitySectionProps) {
	return (
		<div className="flex flex-col gap-4">
			<h3 className="text-lg font-semibold">Review activity</h3>
			<div className="flex flex-col gap-2">
				{isLoading ? (
					Array.from({ length: 3 }, (_, i) => (
						<ReviewActivityCard key={i} isLoading providerType={providerType} />
					))
				) : reviewActivity.length > 0 ? (
					reviewActivity.map((activity) => (
						<ReviewActivityCard
							key={activity.id}
							isLoading={false}
							state={activity.state}
							submittedAt={activity.submittedAt}
							htmlUrl={activity.htmlUrl}
							pullRequest={activity.pullRequest}
							repositoryName={activity.pullRequest?.repository?.name}
							score={activity.score}
							providerType={providerType}
						/>
					))
				) : (
					<EmptyState
						icon={<CodeReviewIcon className="size-6" size={24} />}
						title="No review activity"
						description={
							currUserIsDashboardUser
								? `No review activity that counts yet. Try a wider timeframe.`
								: `${personLabel} has no review activity that counts in this timeframe.`
						}
					/>
				)}
			</div>
			{canViewAll && (
				<Button
					type="button"
					variant="link"
					size="inline"
					className="w-fit text-sm"
					onClick={onViewAll}
				>
					View all review activity
					<ArrowRightIcon data-icon="inline-end" />
				</Button>
			)}
		</div>
	);
}
