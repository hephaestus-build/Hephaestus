import { type ReactNode, useId } from "react";

import { cn } from "cn";

export interface CardProps {
	title?: string;
	/** One sentence under the title: what this area is for. */
	description?: ReactNode;
	/** A control that belongs to the whole card, beside its title. */
	action?: ReactNode;
	children?: ReactNode;
	className?: string;
}

/** One area of a settings page: a titled surface on the page's ground, as Chrome's own settings are. */
export function Card({ title, description, action, children, className }: CardProps) {
	const titleId = useId();
	return (
		<section
			aria-labelledby={title === undefined ? undefined : titleId}
			className={cn(
				"flex flex-col gap-4 rounded-xl border border-border bg-card p-5 text-card-foreground shadow-xs",
				className,
			)}
		>
			{title === undefined && description === undefined && action === undefined ? null : (
				<div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
					<div className="flex min-w-0 flex-col gap-1">
						{title === undefined ? null : (
							<h2 id={titleId} className="text-base font-semibold tracking-tight">
								{title}
							</h2>
						)}
						{description === undefined ? null : (
							<div className="text-sm text-muted-foreground">{description}</div>
						)}
					</div>
					{action}
				</div>
			)}
			{children}
		</section>
	);
}
