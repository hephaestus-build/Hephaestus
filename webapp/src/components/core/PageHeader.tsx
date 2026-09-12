import type { ReactNode } from "react";

import { cn } from "cn";

export interface PageHeaderProps {
	icon: ReactNode;
	title: string;
	description?: ReactNode;
	actions?: ReactNode;
	className?: string;
}

export function PageHeader({ icon, title, description, actions, className }: PageHeaderProps) {
	return (
		<header className={cn("flex flex-wrap items-start justify-between gap-4", className)}>
			<div className="flex min-w-0 flex-1 items-start gap-3">
				<div className="mt-1 shrink-0 text-muted-foreground [&_svg]:size-6" aria-hidden="true">
					{icon}
				</div>
				<div className="min-w-0 space-y-1">
					<h1 className="break-words text-2xl font-semibold tracking-tight">{title}</h1>
					{description && <p className="max-w-2xl text-sm text-muted-foreground">{description}</p>}
				</div>
			</div>
			{actions && (
				<div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">{actions}</div>
			)}
		</header>
	);
}
