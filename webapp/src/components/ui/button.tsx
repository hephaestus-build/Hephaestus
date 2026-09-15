import { Button as ButtonPrimitive } from "@base-ui/react/button";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "cn";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add button` drops the following; re-apply them.
 *
 * 1. `quiet`, the ghost that rests at muted contrast: toolbar and row actions that sit beside the
 *    content they act on, and come up to full contrast on hover and focus.
 * 2. `warning-outline`, the outlined `destructive-outline` in the warning tone, for an action inside
 *    a banner that is already warning-coloured.
 * 3. The `shape` axis. `pill` is applied after `size`, so it overrides whatever radius the size chose.
 * 4. `aria-pressed` is styled wherever `aria-expanded` already was: a toggle that is on has to look
 *    it, and upstream styles only the expanded case.
 */

const buttonVariants = cva(
	"focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:aria-invalid:border-destructive/50 rounded-lg border border-transparent bg-clip-padding text-sm font-medium focus-visible:ring-3 aria-invalid:ring-3 [&_svg:not([class*='size-'])]:size-4 inline-flex items-center justify-center whitespace-nowrap transition-all disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none shrink-0 [&_svg]:shrink-0 outline-none group/button select-none",
	{
		variants: {
			variant: {
				// Plain `hover:`, not `[a]:hover:` — that variant compiles to `&:is(a):hover`, which gives
				// hover feedback to a button rendered as a link and to no other.
				default: "bg-primary text-primary-foreground hover:bg-primary/80",
				outline:
					"border-border bg-background hover:bg-muted hover:text-foreground dark:bg-input/30 dark:border-input dark:hover:bg-input/50 aria-expanded:bg-muted aria-expanded:text-foreground aria-pressed:bg-muted aria-pressed:text-foreground",
				secondary:
					"bg-secondary text-secondary-foreground hover:bg-secondary/80 aria-expanded:bg-secondary aria-expanded:text-secondary-foreground aria-pressed:bg-secondary aria-pressed:text-secondary-foreground",
				ghost:
					"hover:bg-muted hover:text-foreground dark:hover:bg-muted/50 aria-expanded:bg-muted aria-expanded:text-foreground aria-pressed:bg-muted aria-pressed:text-foreground",
				// Ghost, but quiet until it is wanted: toolbar and row actions that sit beside content
				// they must not compete with, and come up to full contrast on hover and focus.
				quiet:
					"text-muted-foreground hover:bg-muted hover:text-foreground dark:hover:bg-muted/50 aria-expanded:bg-muted aria-expanded:text-foreground aria-pressed:bg-muted aria-pressed:text-foreground",
				destructive:
					"bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/20 dark:focus-visible:ring-destructive/40 dark:bg-destructive/70",
				"destructive-outline":
					"border-destructive/30 text-destructive bg-background hover:bg-destructive/10 hover:text-destructive focus-visible:ring-destructive/20 dark:focus-visible:ring-destructive/40 dark:bg-input/30 dark:hover:bg-destructive/20",
				// The same outlined shape in the warning tone, for an action offered inside a banner
				// that is already warning-coloured and would swallow a neutral button.
				"warning-outline":
					"border-warning/50 text-warning bg-transparent hover:bg-warning/20 hover:text-warning focus-visible:ring-warning/20 dark:focus-visible:ring-warning/40",
				link: "text-primary underline-offset-4 hover:underline",
			},
			size: {
				default:
					"h-8 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
				xs: "h-6 gap-1 rounded-[min(var(--radius-md),10px)] px-2 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3",
				sm: "h-7 gap-1 rounded-[min(var(--radius-md),12px)] px-2.5 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3.5",
				lg: "h-9 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-3 has-data-[icon=inline-start]:pl-3",
				// A button that sits inside a sentence: no height, no padding box, and the type size inherited
				// from the prose around it. `text-[length:inherit]` and not `text-inherit` — that one sets
				// the *colour*, and since it wins the merge against a variant it repaints `variant="link"`
				// from `text-primary` to the surrounding grey.
				inline: "h-auto gap-1 rounded-sm p-0 font-normal text-[length:inherit]",
				icon: "size-8",
				"icon-xs":
					"size-6 rounded-[min(var(--radius-md),10px)] in-data-[slot=button-group]:rounded-lg [&_svg:not([class*='size-'])]:size-3",
				"icon-sm":
					"size-7 rounded-[min(var(--radius-md),12px)] in-data-[slot=button-group]:rounded-lg",
				"icon-lg": "size-9",
			},
			// Applied after `size`, so the pill overrides whatever radius the size chose.
			shape: {
				default: "",
				pill: "rounded-full",
			},
		},
		defaultVariants: {
			variant: "default",
			size: "default",
			shape: "default",
		},
	},
);

function Button({
	className,
	variant = "default",
	size = "default",
	shape = "default",
	...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
	return (
		<ButtonPrimitive
			data-slot="button"
			className={cn(buttonVariants({ variant, size, shape, className }))}
			{...props}
		/>
	);
}

export { Button, buttonVariants };
