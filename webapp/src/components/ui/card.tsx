import { cva, type VariantProps } from "class-variance-authority";
import type * as React from "react";

import { cn } from "cn";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add card` drops the following; re-apply them.
 *
 * 1. `variant` and `flush`. The edge is a ring, not a border, so a caller's `border-dashed` or
 *    `hover:border-*` painted nothing; the variants own those treatments, and `flush` drops the
 *    vertical padding and gap for a card whose content — a table, a list — draws its own edges.
 * 2. `CardHeader` takes `band`: a tinted strip flush to the top edge, which the card makes room for.
 */
const cardVariants = cva(
	"ring-foreground/10 bg-card text-card-foreground gap-4 overflow-hidden rounded-xl py-4 text-sm ring-1 has-data-[slot=card-footer]:pb-0 has-[>img:first-child]:pt-0 has-[>[data-band]]:pt-0 data-[size=sm]:gap-3 data-[size=sm]:py-3 data-[size=sm]:has-data-[slot=card-footer]:pb-0 *:[img:first-child]:rounded-t-xl *:[img:last-child]:rounded-b-xl group/card flex flex-col",
	{
		variants: {
			variant: {
				default: "",
				muted: "bg-muted/40",
				// Wrapped in a link or given an onClick: the surface answers the pointer.
				interactive: "transition-colors hover:bg-accent/50 group-hover:bg-accent/50",
				destructive: "ring-destructive/50",
				// The dashed edge says the space is meant to hold something.
				dashed: "border border-dashed ring-0",
			},
			flush: {
				true: "gap-0 py-0",
				false: "",
			},
		},
		defaultVariants: {
			variant: "default",
			flush: false,
		},
	},
);

function Card({
	className,
	size = "default",
	variant = "default",
	flush = false,
	...props
}: React.ComponentProps<"div"> & VariantProps<typeof cardVariants> & { size?: "default" | "sm" }) {
	return (
		<div
			data-slot="card"
			data-size={size}
			data-variant={variant}
			className={cn(cardVariants({ variant, flush, className }))}
			{...props}
		/>
	);
}

function CardHeader({
	className,
	band = false,
	...props
}: React.ComponentProps<"div"> & {
	/** A tinted strip flush to the card's top edge, divided from the body below. */
	band?: boolean;
}) {
	return (
		<div
			data-slot="card-header"
			data-band={band || undefined}
			className={cn(
				"gap-1 rounded-t-xl px-4 group-data-[size=sm]/card:px-3 [.border-b]:pb-4 group-data-[size=sm]/card:[.border-b]:pb-3 group/card-header @container/card-header grid auto-rows-min items-start has-data-[slot=card-action]:grid-cols-[1fr_auto] has-data-[slot=card-description]:grid-rows-[auto_auto]",
				band && "border-b bg-muted/40 pt-4 group-data-[size=sm]/card:pt-3",
				className,
			)}
			{...props}
		/>
	);
}

function CardTitle({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="card-title"
			className={cn(
				"text-base leading-snug font-medium group-data-[size=sm]/card:text-sm",
				className,
			)}
			{...props}
		/>
	);
}

function CardDescription({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="card-description"
			className={cn("text-muted-foreground text-sm", className)}
			{...props}
		/>
	);
}

function CardAction({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="card-action"
			className={cn("col-start-2 row-span-2 row-start-1 self-start justify-self-end", className)}
			{...props}
		/>
	);
}

function CardContent({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="card-content"
			className={cn("px-4 group-data-[size=sm]/card:px-3", className)}
			{...props}
		/>
	);
}

function CardFooter({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="card-footer"
			className={cn(
				"bg-muted/50 rounded-b-xl border-t p-4 group-data-[size=sm]/card:p-3 flex items-center",
				className,
			)}
			{...props}
		/>
	);
}

export { Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle };
