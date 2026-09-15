import { cn } from "cn";
import type { ReactNode } from "react";

import { Card, CardContent } from "@/components/ui/card";

export interface EmptyStateProps {
	icon: ReactNode;
	title: string;
	description?: string;
	action?: ReactNode;
	className?: string;
}

export function EmptyState({ icon, title, description, action, className }: EmptyStateProps) {
	return (
		<Card variant="dashed" className={cn("min-h-60", className)}>
			<CardContent className="flex flex-1 flex-col items-center justify-center py-8 px-4 text-center">
				<div aria-hidden="true" className="rounded-full bg-muted text-muted-foreground p-3 mb-3">
					{icon}
				</div>
				<h3 className="font-medium text-lg mb-1">{title}</h3>
				{description && (
					<p className="text-muted-foreground text-sm mb-4 max-w-md">{description}</p>
				)}
				{action && <div className="mt-2">{action}</div>}
			</CardContent>
		</Card>
	);
}
