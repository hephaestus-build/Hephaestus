import { mergeProps } from "@base-ui/react/merge-props";
import { useRender } from "@base-ui/react/use-render";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "cn";

const badgeVariants = cva(
	"h-5 gap-1 rounded-4xl border border-transparent px-2 py-0.5 text-xs font-medium transition-all has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&>svg]:size-3! inline-flex items-center justify-center w-fit whitespace-nowrap shrink-0 [&>svg]:pointer-events-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive overflow-hidden group/badge",
	{
		variants: {
			variant: {
				default: "bg-primary text-primary-foreground [a]:hover:bg-primary/80",
				secondary: "bg-secondary text-secondary-foreground [a]:hover:bg-secondary/80",
				destructive:
					"bg-destructive/10 [a]:hover:bg-destructive/20 focus-visible:ring-destructive/20 dark:focus-visible:ring-destructive/40 text-destructive dark:bg-destructive/20",
				success: "bg-success/10 text-success dark:bg-success/20",
				warning: "bg-warning/10 text-warning dark:bg-warning/20",
				outline: "border-border text-foreground [a]:hover:bg-muted [a]:hover:text-muted-foreground",
				// Outlined but resting at muted contrast: a tag beside a name that must not read as a status.
				muted: "border-border text-muted-foreground [a]:hover:bg-muted",
				// The mentor's own accent, for the one place Heph is introduced rather than shown.
				mentor: "border-mentor/20 bg-mentor/5 text-mentor",
				ghost: "hover:bg-muted hover:text-muted-foreground dark:hover:bg-muted/50",
				link: "text-primary underline-offset-4 hover:underline",
			},
			size: {
				default: "",
				// A count or identifier inside a control: squarer, tighter, and not bold against its host.
				sm: "rounded-sm px-1 font-normal",
				xs: "h-4 px-1.5 text-2xs [&>svg]:size-2.5!",
				// Wraps and grows with its content, for a pill that carries a sentence rather than a word.
				lg: "h-auto gap-1.5 px-2.5 py-1 text-sm whitespace-normal [&>svg]:size-4!",
			},
		},
		defaultVariants: {
			variant: "default",
			size: "default",
		},
	},
);

function Badge({
	className,
	variant = "default",
	size = "default",
	render,
	...props
}: useRender.ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
	return useRender({
		defaultTagName: "span",
		props: mergeProps<"span">(
			{
				className: cn(badgeVariants({ className, variant, size })),
			},
			props,
		),
		render,
		state: {
			slot: "badge",
			variant,
		},
	});
}

export { Badge, badgeVariants };
