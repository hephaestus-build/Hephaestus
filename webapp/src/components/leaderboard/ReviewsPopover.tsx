import { CheckIcon, CopyIcon } from "@primer/octicons-react";
import { useEffect, useRef, useState } from "react";

import { cn } from "cn";
import type { PullRequestBaseInfo, PullRequestInfo } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { CardTitle } from "@/components/ui/card";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ScrollArea } from "@/components/ui/scroll-area";
import { getProviderTerms, getPullRequestStateIcon, type ProviderType } from "@/lib/provider";

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
	const [isOpen, setIsOpen] = useState(false);
	const [showCopySuccess, setShowCopySuccess] = useState(false);
	const copySuccessTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
	const hasReviews = reviewedPullRequests.length > 0;
	const terms = getProviderTerms(providerType);
	const { icon: PrIcon } = getPullRequestStateIcon(providerType, "OPEN");

	// Sort reviewed PRs by repository name and PR number
	const sortedReviewedPullRequests = [...reviewedPullRequests].sort((a, b) => {
		if (a.repository?.name === b.repository?.name) {
			return a.number - b.number;
		}
		return (a.repository?.name ?? "").localeCompare(b.repository?.name ?? "");
	});

	// Helper function to copy PR URLs to clipboard
	const copyPullRequests = () => {
		if (!hasReviews) return;

		// Create HTML for clipboard
		const htmlList = `<ul>
      ${sortedReviewedPullRequests
				.map(
					(pullRequest) =>
						`<li><a href="${pullRequest.htmlUrl}">${pullRequest.repository?.name ?? ""} #${pullRequest.number}</a></li>`,
				)
				.join("\n")}
    </ul>`;

		// Create markdown text
		const plainText = sortedReviewedPullRequests
			.map(
				(pullRequest) =>
					`[${pullRequest.repository?.name ?? ""} #${pullRequest.number}](${pullRequest.htmlUrl})`,
			)
			.join("\n");

		try {
			// Try to use the ClipboardItem API (modern browsers)
			const clipboardItem = new ClipboardItem({
				"text/html": new Blob([htmlList], { type: "text/html" }),
				"text/plain": new Blob([plainText], { type: "text/plain" }),
			});

			navigator.clipboard.write([clipboardItem]).catch(() => {
				// Fallback to plain text if html copying fails
				void navigator.clipboard.writeText(plainText);
			});
		} catch (_e) {
			// Basic fallback for older browsers
			void navigator.clipboard.writeText(plainText);
		}

		setShowCopySuccess(true);

		if (copySuccessTimerRef.current !== null) {
			clearTimeout(copySuccessTimerRef.current);
		}
		copySuccessTimerRef.current = setTimeout(() => {
			setShowCopySuccess(false);
			copySuccessTimerRef.current = null;
		}, 2000);
	};

	useEffect(() => {
		return () => {
			if (copySuccessTimerRef.current !== null) {
				clearTimeout(copySuccessTimerRef.current);
				copySuccessTimerRef.current = null;
			}
		};
	}, []);

	return (
		<Popover open={isOpen} onOpenChange={setIsOpen}>
			<PopoverTrigger
				render={
					<Button
						variant="outline"
						size="sm"
						disabled={!hasReviews}
						className={cn(
							"flex items-center gap-1",
							!highlight
								? "text-provider-muted-foreground"
								: "border-primary bg-accent hover:bg-foreground hover:text-background",
						)}
						onClick={(e) => e.stopPropagation()}
					>
						<PrIcon size={16} />
						{reviewedPullRequests.length}
					</Button>
				}
			/>
			<PopoverContent
				className="space-y-2 w-60"
				sideOffset={5}
				onClick={(e) => e.stopPropagation()}
			>
				<div className="flex flex-wrap justify-between items-center gap-4">
					<CardTitle className="flex items-center gap-2">
						<PrIcon size={20} />
						<h4 className="font-medium leading-none">Reviewed {terms.pullRequestsShort}</h4>
					</CardTitle>
					<Button variant="outline" size="icon" onClick={copyPullRequests}>
						{showCopySuccess ? (
							<CheckIcon className="text-success size-4" />
						) : (
							<CopyIcon className="size-4" />
						)}
					</Button>
				</div>
				{hasReviews && (
					<ScrollArea
						className="rounded-md -mr-2.5"
						style={{
							height: `min(200px, ${36 * sortedReviewedPullRequests.length}px)`,
						}}
					>
						<div className="flex flex-col rounded-md text-muted-foreground text-sm pr-2.5">
							{sortedReviewedPullRequests.map((pullRequest) => (
								<a
									key={pullRequest.id}
									href={pullRequest.htmlUrl}
									target="_blank"
									rel="noopener noreferrer"
									className={cn(
										"px-3 py-2 hover:bg-accent rounded-md justify-start",
										"transition-colors duration-200",
									)}
									title={pullRequest.title}
								>
									{pullRequest.repository?.name ?? ""} #{pullRequest.number}
								</a>
							))}
						</div>
					</ScrollArea>
				)}
			</PopoverContent>
		</Popover>
	);
}
