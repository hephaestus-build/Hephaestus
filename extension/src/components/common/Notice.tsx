import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";

export interface NoticeProps {
	icon: LucideIcon;
	title: string;
	children?: ReactNode;
	tone?: "neutral" | "info" | "warning" | "destructive";
	/** A control for the reader's next step: sign in, grant, retry. */
	action?: ReactNode;
}

const TONE_CLASSES: Record<NonNullable<NoticeProps["tone"]>, { box: string; icon: string }> = {
	neutral: { box: "border-border bg-card", icon: "text-muted-foreground" },
	info: { box: "border-mentor/30 bg-mentor/5", icon: "text-mentor" },
	warning: { box: "border-warning/30 bg-warning/5", icon: "text-warning" },
	destructive: { box: "border-destructive/30 bg-destructive/5", icon: "text-destructive" },
};

/** A state that replaces a region's content: what is true, and what the reader can do about it. */
export function Notice({ icon: Icon, title, children, tone = "neutral", action }: NoticeProps) {
	const classes = TONE_CLASSES[tone];
	return (
		<section className={cn("flex gap-3 rounded-lg border p-3.5", classes.box)} aria-label={title}>
			<Icon aria-hidden className={cn("mt-0.5 size-4 shrink-0", classes.icon)} />
			<div className="flex min-w-0 flex-col gap-1.5">
				<h2 className="text-sm font-semibold">{title}</h2>
				{children === undefined ? null : (
					<div className="text-sm text-muted-foreground">{children}</div>
				)}
				{action === undefined ? null : (
					<div className="flex flex-wrap items-center gap-2 pt-1">{action}</div>
				)}
			</div>
		</section>
	);
}
