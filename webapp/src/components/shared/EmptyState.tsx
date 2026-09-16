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
			<CardContent className="flex flex-1 flex-col items-center justify-center px-4 py-8 text-center">
				<div aria-hidden="true" className="mb-3 rounded-full bg-muted p-3 text-muted-foreground">
					{icon}
				</div>
				<h3 className="mb-1 text-lg font-medium">{title}</h3>
				{description && (
					<p className="mb-4 max-w-md text-sm text-muted-foreground">{description}</p>
				)}
				{action && <div className="mt-2">{action}</div>}
			</CardContent>
		</Card>
	);
}
