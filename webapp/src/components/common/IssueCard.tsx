import { format } from "date-fns";

import { cn } from "cn";
import type { LabelInfo } from "@/api/types.gen";
import { FormattedTitle } from "@/components/common/FormattedTitle";
import { LabelBadge } from "@/components/common/LabelBadge";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { getPullRequestStateIcon, type ProviderType } from "@/lib/provider";
import { hasText } from "@/lib/text";

export interface IssueCardProps {
	isLoading: boolean;
	title?: string;
	number?: number;
	additions?: number;
	deletions?: number;
	htmlUrl?: string;
	repositoryName?: string;
	createdAt?: Date;
	state?: "OPEN" | "CLOSED" | "MERGED";
	isDraft?: boolean;
	isMerged?: boolean;
	pullRequestLabels?: readonly LabelInfo[];
	children?: React.ReactNode;
	/** If true, the card will not be wrapped in an <a> tag */
	noLinkWrapper?: boolean;
	/** Additional content to display in the right side of the card header */
	rightContent?: React.ReactNode;
	/** Optional click handler for the card */
	onClick?: () => void;
	/** Optional className to apply to the card */
	className?: string;
	/** Provider type for rendering correct icons */
	providerType?: ProviderType;
}

const NO_LABELS: readonly LabelInfo[] = [];

export function IssueCard({
	isLoading,
	title,
	number,
	additions,
	deletions,
	htmlUrl,
	repositoryName,
	createdAt,
	state,
	isDraft,
	isMerged,
	pullRequestLabels = NO_LABELS,
	children,
	noLinkWrapper = false,
	rightContent,
	onClick,
	className,
	providerType = "GITHUB",
}: IssueCardProps) {
	// Determine the PR state icon and color based on provider
	const effectiveState = isMerged === true ? "MERGED" : (state ?? "OPEN");
	const { icon: StateIcon, colorClass: color } = getPullRequestStateIcon(
		providerType,
		effectiveState,
		isDraft,
	);

	// Format the date as MMM D (e.g., "Jan 15")
	const formattedDate = createdAt ? format(createdAt, "MMM d") : "";

	const cardContent = (
		<Card
			flush
			variant={!noLinkWrapper || onClick ? "interactive" : "default"}
			className={cn({ "cursor-pointer": !noLinkWrapper || onClick }, className)}
			onClick={onClick}
		>
			<div
				className={cn("flex flex-col gap-1 p-4", {
					"pb-0": isLoading || pullRequestLabels.length > 0,
				})}
			>
				<div className="flex items-center justify-between gap-2 text-sm text-provider-muted-foreground">
					<span className="flex items-center justify-center space-x-1 font-medium">
						{isLoading ? (
							<>
								<Skeleton className="size-5 bg-success/30" />
								<Skeleton className="h-4 w-16 lg:w-36" />
							</>
						) : (
							<>
								<StateIcon className={`mr-2 ${color}`} size={18} />
								<span className="whitespace-nowrap">
									{hasText(htmlUrl) && hasText(repositoryName) && noLinkWrapper ? (
										<>
											<a
												href={htmlUrl}
												target="_blank"
												rel="noopener noreferrer"
												className="hover:underline"
												onClick={(e) => e.stopPropagation()}
											>
												{repositoryName} #{number}
											</a>
											{formattedDate && <span> on {formattedDate}</span>}
										</>
									) : (
										<>
											{repositoryName} #{number}
											{formattedDate && <> on {formattedDate}</>}
										</>
									)}
								</span>
							</>
						)}
					</span>
					<span className="flex items-center gap-2">
						{isLoading ? (
							<>
								<Skeleton className="h-4 w-8 bg-success/30" />
								<Skeleton className="h-4 w-8 bg-destructive/20" />
							</>
						) : (
							<>
								{additions !== undefined && (
									<span className="font-bold text-provider-success-foreground">+{additions}</span>
								)}
								{deletions !== undefined && (
									<span className="font-bold text-provider-danger-foreground">-{deletions}</span>
								)}
							</>
						)}
					</span>
				</div>

				<div className="flex justify-between leading-normal font-medium contain-inline-size">
					{isLoading ? (
						<Skeleton className="mb-4 h-6 w-3/4" />
					) : (
						<FormattedTitle title={title ?? ""} />
					)}
					{rightContent}
				</div>
			</div>

			{!isLoading && pullRequestLabels.length > 0 && (
				<div className="flex flex-row flex-wrap items-center gap-2 p-4 pt-2">
					{pullRequestLabels.map((label) => (
						<LabelBadge key={label.id} label={label.name} color={label.color} />
					))}
				</div>
			)}
			{children}
		</Card>
	);

	// If noLinkWrapper is true, return the card without wrapping it in an <a> tag
	if (noLinkWrapper) {
		return cardContent;
	}

	// Otherwise wrap in a link
	return (
		<a
			href={htmlUrl}
			target="_blank"
			rel="noopener noreferrer"
			className="block w-full"
			onClick={(e) => {
				if (onClick) {
					e.preventDefault();
					onClick();
				}
			}}
		>
			{cardContent}
		</a>
	);
}
