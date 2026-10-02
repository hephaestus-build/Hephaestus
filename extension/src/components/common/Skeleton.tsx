import type { ComponentProps } from "react";

import { cn } from "cn";

export function Skeleton({ className, ...props }: ComponentProps<"div">) {
	return (
		<div
			className={cn("animate-pulse rounded-md bg-muted motion-reduce:animate-none", className)}
			{...props}
		/>
	);
}
