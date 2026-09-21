import { ArrowRightIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { PullRequestInfo } from "@/api/types.gen";
import { EmptyState } from "@/components/common/EmptyState";
import { IssueCard } from "@/components/common/IssueCard";
import { getPullRequestStateIcon } from "@/components/icons/provider-icons";
import { Button } from "@/components/ui/button";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

export interface OpenPullRequestsSectionProps {
	providerType: ProviderType;
	pullRequests: readonly PullRequestInfo[];
	isLoading: boolean;
	/** Shown under the empty-state title; the caller knows whose profile this is. */
	emptyMessage: string;
	canViewAll: boolean;
	onViewAll: () => void;
}

export function OpenPullRequestsSection({
	providerType,
	pullRequests,
	isLoading,
	emptyMessage,
	canViewAll,
	onViewAll,
}: OpenPullRequestsSectionProps) {
	const terms = getProviderTerms(providerType);
	const { icon: PrIcon } = getPullRequestStateIcon(providerType, "OPEN");

	let list: ReactNode;
	if (isLoading) {
		list = Array.from({ length: 2 }, (_, i) => (
			<IssueCard key={i} isLoading providerType={providerType} />
		));
	} else if (pullRequests.length > 0) {
		list = pullRequests.map((pullRequest) => (
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
		));
	} else {
		list = (
			<EmptyState
				icon={<PrIcon className="size-6" size={24} />}
				title={`No open ${terms.pullRequests.toLowerCase()}`}
				description={emptyMessage}
			/>
		);
	}

	return (
		<div className="flex flex-col gap-4" aria-busy={isLoading}>
			<h3 className="text-lg font-semibold">Open {terms.pullRequests.toLowerCase()}</h3>
			<div className="flex flex-col gap-2">{list}</div>
			{canViewAll && (
				<Button type="button" variant="link" size="inline" className="w-fit" onClick={onViewAll}>
					View all {terms.pullRequests.toLowerCase()}
					<ArrowRightIcon data-icon="inline-end" />
				</Button>
			)}
		</div>
	);
}
