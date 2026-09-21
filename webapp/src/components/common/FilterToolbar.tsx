import { XIcon } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import { Button } from "@/components/ui/button";

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
