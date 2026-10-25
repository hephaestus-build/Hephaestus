import { Checkbox as CheckboxPrimitive } from "@base-ui/react/checkbox";
import { CheckIcon } from "lucide-react";

import { cn } from "cn";

function Checkbox({ className, ...props }: CheckboxPrimitive.Root.Props) {
	return (
		<CheckboxPrimitive.Root
			data-slot="checkbox"
			// `data-disabled:`, never `disabled:`: the box is a `<span role="checkbox">` with a visually
			// hidden `<input>` beside it, and `:disabled` matches only form elements — so a `disabled:`
			// rule compiles fine and leaves a disabled checkbox looking entirely live.
			// In a choice card the card takes the focus ring, so a valid box drops its own. Not upstream's
			// `group-has-[:focus-visible]/field-label:*`: it loses to `focus-visible:*` on specificity, and
			// it also matches a `FieldLabel` that is no card and draws no ring.
			className={cn(
				"peer relative flex size-4 shrink-0 items-center justify-center rounded-xs border border-input transition-colors outline-none group-has-disabled/field:opacity-50 after:absolute after:-inset-x-3 after:-inset-y-2 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 group-has-[>[data-slot=field]]/field-label:focus-visible:not-aria-invalid:ring-0 group-has-[>[data-slot=field]]/field-label:focus-visible:not-aria-invalid:not-data-checked:border-input aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 aria-invalid:aria-checked:border-primary dark:bg-input/30 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 data-checked:border-primary data-checked:bg-primary data-checked:text-primary-foreground group-has-[>[data-slot=field]]/field-label:focus-visible:not-aria-invalid:data-checked:border-primary dark:data-checked:bg-primary data-disabled:cursor-not-allowed data-disabled:opacity-50",
				className,
			)}
			{...props}
		>
			<CheckboxPrimitive.Indicator
				data-slot="checkbox-indicator"
				className="grid place-content-center text-current transition-none [&>svg]:size-3.5"
			>
				<CheckIcon />
			</CheckboxPrimitive.Indicator>
		</CheckboxPrimitive.Root>
	);
}

export { Checkbox };
