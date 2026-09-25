import { Fragment } from "react";

import { cn } from "cn";
import { HIT_AREA_24 } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import {
	BreadcrumbItem,
	BreadcrumbList,
	BreadcrumbPage,
	BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";

export interface DetailPathCrumb {
	label: string;
	/** The depth to close down to: 0 is the page, 1 the first level over it. */
	depth: number;
}

/**
 * What a level is handed so its header can say where it sits: the crumbs behind it and the close.
 */
export interface LevelPath {
	/** The page and the levels behind this one, outermost first. */
	behind: DetailPathCrumb[];
	/** Closes the stack down to a depth; a crumb closes it down to its own. */
	onClose: (depth: number) => void;
}

export interface DetailPathProps extends LevelPath {
	/** What this level is: "Group", "Practice", "All practice groups". */
	current: string;
	className?: string;
}

/**
 * The eyebrow over a level's title, read off the route's detail stack: the page and the levels
 * behind, by name, then what this level is. A crumb behind is an `InlineLink` that closes down to
 * it, and the path is the one place that says both where the reader is and how they got there.
 * The stack never goes deeper than three levels over the page, so a counter would be true and
 * useless; a named path answers "where am I" in one line.
 *
 * The breadcrumb primitive's list, without its `nav`: covered levels stay in the accessibility
 * tree, and two landmarks with the same name would be one too many. Its link is not used either,
 * since it paints its own hover where the house link rule wants mentor blue.
 */
export function DetailPath({ behind, current, onClose, className }: DetailPathProps) {
	return (
		<BreadcrumbList aria-label="Path" className={className}>
			{behind.map((crumb) => (
				<Fragment key={crumb.depth}>
					<BreadcrumbItem>
						<InlineLink
							className={cn(HIT_AREA_24, "text-muted-foreground")}
							onClick={() => onClose(crumb.depth)}
						>
							{crumb.label}
						</InlineLink>
					</BreadcrumbItem>
					<BreadcrumbSeparator className="[&>svg]:size-3" />
				</Fragment>
			))}
			<BreadcrumbItem>
				{/* A level is a place inside the page, not a page of its own. */}
				<BreadcrumbPage aria-current="location">{current}</BreadcrumbPage>
			</BreadcrumbItem>
		</BreadcrumbList>
	);
}
