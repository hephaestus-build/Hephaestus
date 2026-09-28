import { XIcon } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import { Button } from "@/components/ui/button";

/**
 * The value a filter's "everything" choice carries in its select. Base UI treats "" as "no
 * selection", so the choice needs a value of its own; it stands for "no filter", and a caller turns
 * it back into `undefined` before it can reach a URL, where it would filter for a value nothing
 * ever has.
 */
export const ALL_OPTION = "__all";

export interface FilterToolbarProps {
	children: ReactNode;
	hasFilter: boolean;
	onReset: () => void;
	actions?: ReactElement | undefined;
}

export function FilterToolbar({ children, hasFilter, onReset, actions }: FilterToolbarProps) {
	return (
		<div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
			{children}
			{hasFilter && (
				<Button variant="ghost" size="sm" className="h-8" onClick={onReset}>
					Reset
					<XIcon aria-hidden data-icon="inline-end" />
				</Button>
			)}
			{actions !== undefined && <div className="flex items-center gap-2 sm:ml-auto">{actions}</div>}
		</div>
	);
}
