import { CodeReviewIcon } from "@primer/octicons-react";
import { ArrowRightIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { ProfileReviewActivity } from "@/api/types.gen";
import { EmptyState } from "@/components/common/EmptyState";
import { Button } from "@/components/ui/button";
import type { ProviderType } from "@/lib/provider/provider-terms";

import { ReviewActivityCard } from "./ReviewActivityCard";

export interface ReviewActivitySectionProps {
	providerType: ProviderType;
	reviewActivity: readonly ProfileReviewActivity[];
	isLoading: boolean;
	/** Shown under "No review activity"; the caller knows whose profile this is. */
	emptyMessage: string;
	canViewAll: boolean;
	onViewAll: () => void;
}

export function ReviewActivitySection({
	providerType,
	reviewActivity,
	isLoading,
	emptyMessage,
	canViewAll,
	onViewAll,
}: ReviewActivitySectionProps) {
	let list: ReactNode;
	if (isLoading) {
		list = Array.from({ length: 3 }, (_, i) => (
			<ReviewActivityCard key={i} isLoading providerType={providerType} />
		));
	} else if (reviewActivity.length > 0) {
		list = reviewActivity.map((activity) => (
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
		));
	} else {
		list = (
			<EmptyState
				icon={<CodeReviewIcon className="size-6" size={24} />}
				title="No review activity"
				description={emptyMessage}
			/>
		);
	}

	return (
		<div className="flex flex-col gap-4">
			<h3 className="text-lg font-semibold">Review activity</h3>
			<div className="flex flex-col gap-2">{list}</div>
			{canViewAll && (
				<Button type="button" variant="link" size="inline" className="w-fit" onClick={onViewAll}>
					View all review activity
					<ArrowRightIcon data-icon="inline-end" />
				</Button>
			)}
		</div>
	);
}
