import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "cn";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add empty` drops the following; re-apply it.
 *
 * `Empty` takes a `variant`. Upstream's base sets `border-dashed` with no border width, so its
 * dashed edge never renders; `outlined` owns both, and `plain` (default) is for an empty state inside
 * a card or panel that already draws the boundary.
 */
const emptyVariants = cva(
	"flex w-full min-w-0 flex-1 flex-col items-center justify-center gap-4 rounded-lg p-6 text-center text-balance",
	{
		variants: {
			variant: {
				plain: "",
				outlined: "border border-dashed",
			},
		},
		defaultVariants: {
			variant: "plain",
		},
	},
);

function Empty({
	className,
	variant = "plain",
	...props
}: React.ComponentProps<"div"> & VariantProps<typeof emptyVariants>) {
	return (
		<div
			data-slot="empty"
			data-variant={variant}
			className={cn(emptyVariants({ variant, className }))}
			{...props}
		/>
	);
}

function EmptyHeader({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="empty-header"
			className={cn("flex max-w-sm flex-col items-center gap-2", className)}
			{...props}
		/>
	);
}

const emptyMediaVariants = cva(
	"mb-2 flex shrink-0 items-center justify-center [&_svg]:pointer-events-none [&_svg]:shrink-0",
	{
		variants: {
			variant: {
				default: "bg-transparent",
				icon: "flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted text-foreground [&_svg:not([class*='size-'])]:size-4",
			},
		},
		defaultVariants: {
			variant: "default",
		},
	},
);

function EmptyMedia({
	className,
	variant = "default",
	...props
}: React.ComponentProps<"div"> & VariantProps<typeof emptyMediaVariants>) {
	return (
		<div
			data-slot="empty-icon"
			data-variant={variant}
			className={cn(emptyMediaVariants({ variant, className }))}
			{...props}
		/>
	);
}

function EmptyTitle({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="empty-title"
			className={cn("text-sm font-medium tracking-tight", className)}
			{...props}
		/>
	);
}

function EmptyDescription({ className, ...props }: React.ComponentProps<"p">) {
	return (
		<div
			data-slot="empty-description"
			className={cn(
				"text-sm/relaxed text-muted-foreground [&>a]:underline [&>a]:underline-offset-4 [&>a:hover]:text-primary",
				className,
			)}
			{...props}
		/>
	);
}

function EmptyContent({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="empty-content"
			className={cn(
				"flex w-full max-w-sm min-w-0 flex-col items-center gap-2.5 text-sm text-balance",
				className,
			)}
			{...props}
		/>
	);
}

export { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle };
