"use client";

import type * as React from "react";

import { cn } from "cn";

function Label({ className, ...props }: React.ComponentProps<"label">) {
	return (
		// oxlint-disable-next-line jsx-a11y/label-has-associated-control -- The primitive; `settings.jsx-a11y.components` maps `Label` back to `label`, so its call sites are the ones this rule checks.
		<label
			data-slot="label"
			// `peer-aria-disabled:` as well as `peer-disabled:`: a native input carries `:disabled`, but
			// Base UI's checkbox, radio and switch are each a `<span role="…">` that `:disabled` cannot
			// match, so a label beside one needs the ARIA hook to dim with it.
			className={cn(
				"flex items-center gap-2 text-sm leading-none font-medium select-none group-data-[disabled=true]:pointer-events-none group-data-[disabled=true]:opacity-50 peer-disabled:cursor-not-allowed peer-disabled:opacity-50 peer-aria-disabled:cursor-not-allowed peer-aria-disabled:opacity-50",
				className,
			)}
			{...props}
		/>
	);
}

export { Label };
