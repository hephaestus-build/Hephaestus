import { cn } from "cn";
import type { ReactElement } from "react";

import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { hasText } from "@/lib/text";

export interface EmptyStateProps {
	icon: ReactElement;
	title: string;
	/** Where the title sits in the page's outline: 1 when it is the page, 2 under a page title. */
	headingLevel: 1 | 2 | 3;
	description?: string;
	action?: ReactElement;
	/** `outlined` marks an empty region inside a page; `plain` fills a page or a panel that has its own edge. */
	variant?: "outlined" | "plain";
	className?: string;
}

export function EmptyState({
	icon,
	title,
	headingLevel,
	description,
	action,
	variant = "outlined",
	className,
}: EmptyStateProps) {
	return (
		<Empty variant={variant} className={cn(variant === "outlined" && "min-h-60", className)}>
			<EmptyHeader>
				<EmptyMedia variant="icon">{icon}</EmptyMedia>
				<EmptyTitle role="heading" aria-level={headingLevel}>
					{title}
				</EmptyTitle>
				{hasText(description) && <EmptyDescription>{description}</EmptyDescription>}
			</EmptyHeader>
			{action && <EmptyContent>{action}</EmptyContent>}
		</Empty>
	);
}
