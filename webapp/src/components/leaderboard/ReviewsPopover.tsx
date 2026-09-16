import { CopyIcon } from "@primer/octicons-react";
import { useState } from "react";
import { toast } from "sonner";

import { cn } from "cn";
import type { PullRequestBaseInfo, PullRequestInfo } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "@/components/ui/popover";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Spinner } from "@/components/ui/spinner";
import { getProviderTerms, getPullRequestStateIcon, type ProviderType } from "@/lib/provider";

import { copyReviewLinks, reviewLinkUrl } from "./review-links";

export type ReviewedPullRequest = PullRequestInfo | PullRequestBaseInfo;

export interface ReviewsPopoverProps {
	reviewedPullRequests: readonly ReviewedPullRequest[];
	highlight?: boolean;
	providerType?: ProviderType;
}

export function ReviewsPopover({
	reviewedPullRequests,
	highlight = false,
	providerType = "GITHUB",
}: ReviewsPopoverProps) {
	const [isCopying, setIsCopying] = useState(false);
	const hasReviews = reviewedPullRequests.length > 0;
	const terms = getProviderTerms(providerType);
	const { icon: PrIcon } = getPullRequestStateIcon(providerType, "OPEN");

	const sortedReviewedPullRequests = [...reviewedPullRequests].sort((a, b) => {
		if (a.repository?.name === b.repository?.name) {
			return a.number - b.number;
		}
		return (a.repository?.name ?? "").localeCompare(b.repository?.name ?? "");
	});

	const links = sortedReviewedPullRequests.map((pullRequest) => ({
		id: pullRequest.id,
		title: pullRequest.title,
		label: `${pullRequest.repository?.name ?? ""} #${pullRequest.number}`.trim(),
		url: reviewLinkUrl(pullRequest.htmlUrl),
	}));
	const copyableLinks = links.flatMap(({ label, url }) => (url ? [{ label, url }] : []));

	const copyLinks = async () => {
		setIsCopying(true);
		try {
			await copyReviewLinks(copyableLinks);
			toast.success("Review links copied");
		} catch {
			toast.error("Could not copy review links");
		} finally {
			setIsCopying(false);
		}
	};

	return (
		<Popover>
			<PopoverTrigger
				render={
					<Button
						variant="outline"
						size="sm"
						disabled={!hasReviews}
						aria-label={`Show ${reviewedPullRequests.length} reviewed ${(reviewedPullRequests.length === 1 ? terms.pullRequest : terms.pullRequests).toLowerCase()}`}
						className={cn(
							!highlight
								? "text-provider-muted-foreground"
								: "border-primary bg-accent hover:bg-foreground hover:text-background",
						)}
						onClick={(e) => e.stopPropagation()}
					>
						<PrIcon size={16} data-icon="inline-start" />
						{reviewedPullRequests.length}
					</Button>
				}
			/>
			<PopoverContent
				className="w-60 space-y-2"
				sideOffset={5}
				onClick={(e) => e.stopPropagation()}
			>
				<div className="flex flex-wrap items-center justify-between gap-4">
					<PopoverTitle className="flex items-center gap-2 leading-none">
						<PrIcon size={20} />
						Reviewed {terms.pullRequestsShort}
					</PopoverTitle>
					<Button
						variant="outline"
						size="icon"
						aria-label={
							isCopying
								? "Copying review links…"
								: `Copy links to reviewed ${terms.pullRequests.toLowerCase()}`
						}
						disabled={isCopying || copyableLinks.length === 0}
						onClick={() => void copyLinks()}
					>
						{isCopying ? <Spinner /> : <CopyIcon className="size-4" />}
					</Button>
				</div>
				{hasReviews && (
					<ScrollArea className="-mr-2.5 rounded-md" viewportClassName="max-h-50">
						<div className="flex flex-col rounded-md pr-2.5 text-sm text-muted-foreground">
							{links.map((pullRequest) => (
								<a
									key={pullRequest.id}
									href={pullRequest.url}
									target="_blank"
									rel="noopener noreferrer"
									className={cn(
										"justify-start rounded-md px-3 py-2",
										pullRequest.url && "transition-colors duration-200 hover:bg-accent",
									)}
									title={pullRequest.title}
								>
									{pullRequest.label}
								</a>
							))}
						</div>
					</ScrollArea>
				)}
			</PopoverContent>
		</Popover>
	);
}
