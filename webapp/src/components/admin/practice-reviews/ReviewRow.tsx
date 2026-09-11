import { Fragment, type ReactNode } from "react";

import { cn } from "cn";
import { type StatusDef, statusToneClass } from "@/components/practice-vocabulary/status-def";

export interface ReviewRowProps {
	status: StatusDef;
	title: ReactNode;
	meta?: ReactNode;
	chips?: ReviewRowChip[];
}

export interface ReviewRowChip {
	key: string;
	width?: string;
	node?: ReactNode;
}

export function ReviewRow({ status, title, meta, chips }: ReviewRowProps) {
	const { icon: Icon } = status;
	return (
		<li className="relative flex items-start gap-3 p-4 transition-colors hover:bg-muted/40 has-[a:focus-visible]:bg-muted/40">
			<span
				aria-hidden
				className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-md border bg-background"
			>
				<Icon className={cn("size-4", statusToneClass(status.badgeVariant))} />
			</span>
			<div className="flex min-w-0 flex-1 flex-wrap items-start justify-between gap-x-4 gap-y-2">
				<div className="min-w-0 flex-1 basis-64 space-y-1">
					<div className="text-sm font-medium [&_a]:after:absolute [&_a]:after:inset-0 [&_a:hover]:underline">
						{title}
					</div>
					{meta && <div className="min-w-0 space-y-0.5 text-xs text-muted-foreground">{meta}</div>}
				</div>
				{chips && chips.length > 0 && (
					<div className="relative flex flex-wrap items-start gap-1.5 lg:flex-nowrap">
						{chips.map((chip) => (
							<span
								key={chip.key}
								className={cn(
									"flex min-w-0 flex-wrap items-center gap-1.5 empty:hidden",
									chip.width && "lg:shrink-0 lg:empty:flex",
									chip.width,
								)}
							>
								{chip.node}
							</span>
						))}
					</div>
				)}
			</div>
		</li>
	);
}

export interface ReviewRowListProps {
	label: string;
	children: ReactNode;
}

export function ReviewRowList({ label, children }: ReviewRowListProps) {
	return (
		<ul aria-label={label} className="divide-y rounded-lg border">
			{children}
		</ul>
	);
}

export function ReviewRowMeta({ items }: { items: ReactNode[] }) {
	const shown = items.filter(Boolean);
	if (shown.length === 0) return null;
	return (
		<p className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 break-words">
			{shown.map((item, index) => (
				<Fragment key={index}>
					{index > 0 && <span aria-hidden>·</span>}
					{item}
				</Fragment>
			))}
		</p>
	);
}
