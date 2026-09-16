import { mergeProps } from "@base-ui/react/merge-props";
import { useRender } from "@base-ui/react/use-render";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "cn";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add badge` drops the following; re-apply them.
 *
 * 1. `success` and `warning`, the two status tones the registry does not ship.
 * 2. `muted`, an outlined tag that must not read as a status, and `mentor`, reserved for where Heph
 *    is introduced rather than wherever Heph appears.
 * 3. `label`, painted by the caller through `--label` and `--label-foreground`: a provider's own label
 *    colour is data, not a theme tone.
 * 4. The `size` axis: `xs` for a count or identifier inside a control, `lg` for a pill that carries a
 *    sentence.
 */
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
				muted: "border-border text-muted-foreground [a]:hover:bg-muted",
				mentor: "border-mentor/20 bg-mentor/5 text-mentor",
				label: "border-(--label) bg-(--label) text-(--label-foreground)",
				ghost: "hover:bg-muted hover:text-muted-foreground dark:hover:bg-muted/50",
				link: "text-primary underline-offset-4 hover:underline",
			},
			size: {
				default: "",
				xs: "h-4 px-1.5 text-2xs [&>svg]:size-2.5!",
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
