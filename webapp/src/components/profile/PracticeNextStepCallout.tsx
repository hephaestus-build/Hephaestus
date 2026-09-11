import { cn } from "cn";
import { LightbulbIcon } from "lucide-react";
import type { ReactNode } from "react";

export interface PracticeNextStepCalloutProps {
	label: string;
	children: ReactNode;
	className?: string;
}
export function PracticeNextStepCallout({
	label,
	children,
	className,
}: PracticeNextStepCalloutProps) {
	return (
		<div className={cn("flex gap-3 rounded-lg border bg-muted/20 p-3", className)}>
			<span className="flex size-7 shrink-0 items-center justify-center rounded-md border bg-background text-mentor/80">
				<LightbulbIcon className="size-3.5" aria-hidden />
			</span>
			<div className="flex min-w-0 flex-col gap-1">
				<p className="text-xs font-medium leading-4 text-muted-foreground">{label}</p>
				<div className="text-pretty text-sm leading-5">{children}</div>
			</div>
		</div>
	);
}
