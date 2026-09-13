import { CheckIcon, type LucideIcon } from "lucide-react";

import { cn } from "cn";

/**
 * The step's own icon until it is answered, then a check. Both are decoration: the heading names the
 * step and the control inside it announces its own state.
 */
export function StepMarker({ icon: Icon, done }: { icon: LucideIcon; done: boolean }) {
	return (
		<span
			aria-hidden="true"
			className={cn(
				"inline-flex size-7 shrink-0 items-center justify-center rounded-md transition-colors [&_svg]:size-4",
				done ? "bg-mentor text-mentor-foreground" : "bg-mentor/10 text-mentor",
			)}
		>
			{done ? <CheckIcon /> : <Icon />}
		</span>
	);
}
