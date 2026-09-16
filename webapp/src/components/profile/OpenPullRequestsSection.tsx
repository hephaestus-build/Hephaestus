import { ArrowRightIcon } from "lucide-react";

import type { PullRequestInfo } from "@/api/types.gen";
import { EmptyState } from "@/components/shared/EmptyState";
import { IssueCard } from "@/components/shared/IssueCard";
import { Button } from "@/components/ui/button";
import { getProviderTerms, getPullRequestStateIcon, type ProviderType } from "@/lib/provider";

export interface OpenPullRequestsSectionProps {
	providerType: ProviderType;
	pullRequests: readonly PullRequestInfo[];
	isLoading: boolean;
	personLabel: string;
	currUserIsDashboardUser: boolean;
	canViewAll: boolean;
	onViewAll: () => void;
}

export function OpenPullRequestsSection({
	providerType,
	pullRequests,
	isLoading,
	personLabel,
	currUserIsDashboardUser,
	canViewAll,
	onViewAll,
}: OpenPullRequestsSectionProps) {
	const terms = getProviderTerms(providerType);
	const { icon: PrIcon } = getPullRequestStateIcon(providerType, "OPEN");

	return (
		<div className="flex flex-col gap-4">
			<h3 className="text-lg font-semibold">Open {terms.pullRequests.toLowerCase()}</h3>
			<div className="flex flex-col gap-2">
				{isLoading ? (
					Array.from({ length: 2 }, (_, i) => (
						<IssueCard key={i} isLoading providerType={providerType} />
					))
				) : pullRequests.length > 0 ? (
					pullRequests.map((pullRequest) => (
						<IssueCard
							key={pullRequest.id}
							isLoading={false}
							additions={pullRequest.additions}
							deletions={pullRequest.deletions}
							number={pullRequest.number}
							repositoryName={pullRequest.repository?.name}
							title={pullRequest.title}
							htmlUrl={pullRequest.htmlUrl}
							state={pullRequest.state}
							isDraft={pullRequest.isDraft}
							isMerged={pullRequest.isMerged}
							createdAt={pullRequest.createdAt}
							pullRequestLabels={pullRequest.labels}
							providerType={providerType}
						/>
					))
				) : (
					<EmptyState
						icon={<PrIcon className="size-6" size={24} />}
						title={`No open ${terms.pullRequests.toLowerCase()}`}
						description={
							currUserIsDashboardUser
								? `${terms.pullRequests} you create will appear here.`
								: `${personLabel} doesn't have any open ${terms.pullRequests.toLowerCase()}.`
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
					View all {terms.pullRequests.toLowerCase()}
					<ArrowRightIcon data-icon="inline-end" />
				</Button>
			)}
		</div>
	);
}
