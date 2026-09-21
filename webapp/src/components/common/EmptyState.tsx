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
	description?: string;
	action?: ReactElement;
	className?: string;
}

export function EmptyState({ icon, title, description, action, className }: EmptyStateProps) {
	return (
		<Empty variant="outlined" className={cn("min-h-60", className)}>
			<EmptyHeader>
				<EmptyMedia variant="icon">{icon}</EmptyMedia>
				<EmptyTitle role="heading" aria-level={3}>
					{title}
				</EmptyTitle>
				{hasText(description) && <EmptyDescription>{description}</EmptyDescription>}
			</EmptyHeader>
			{action && <EmptyContent>{action}</EmptyContent>}
		</Empty>
	);
}
