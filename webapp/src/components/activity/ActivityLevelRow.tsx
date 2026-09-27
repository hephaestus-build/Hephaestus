import { ChevronRightIcon } from "lucide-react";
import { type ReactElement, type ReactNode, useId } from "react";

import { cn } from "cn";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";

import type { ActivityLevel } from "./activity-search";

export interface ActivityLevelRowProps {
	/** The level the row opens; a row with nothing behind it opens nothing. */
	opens?: ActivityLevel;
	/** The row's leading `ItemMedia`. */
	media: ReactElement;
	title: string;
	/** Beside the title and outside the link: a member's login. */
	titleAside?: ReactNode;
	/** The row's figures, which also describe its link. */
	description: ReactNode;
	/** At the row's end: a count. */
	figure?: ReactElement;
}

/**
 * A list row that opens a level over the page. The title is the link and the keyboard stop, and a
 * pseudo-element stretches it over the row so the whole row is the pointer target. The link is
 * described by the row's figures, so what it opens is announced with what it counts (WCAG 2.2
 * SC 2.4.4).
 */
export function ActivityLevelRow({
	opens,
	media,
	title,
	titleAside,
	description,
	figure,
}: ActivityLevelRowProps) {
	const descriptionId = useId();
	return (
		<Item render={<li />} variant="row" className={cn(opens && "relative")}>
			{media}
			<ItemContent className="min-w-0">
				<ItemTitle className="min-w-0">
					{opens ? (
						<InlineLink
							render={<DetailStackLink entry={opens} aria-describedby={descriptionId} />}
							className="truncate after:absolute after:inset-0"
						>
							{title}
						</InlineLink>
					) : (
						title
					)}
					{titleAside}
				</ItemTitle>
				<ItemDescription id={descriptionId} className="line-clamp-none">
					{description}
				</ItemDescription>
			</ItemContent>
			{(figure !== undefined || opens) && (
				<ItemActions className="gap-2">
					{figure}
					{opens && <ChevronRightIcon className="size-4 text-muted-foreground" aria-hidden />}
				</ItemActions>
			)}
		</Item>
	);
}
