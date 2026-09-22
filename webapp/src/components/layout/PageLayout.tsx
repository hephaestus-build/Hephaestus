import type { ComponentProps } from "react";

import { cn } from "cn";

export type PageLayoutProps = ComponentProps<"div">;

export function PageLayout({ className, ...props }: PageLayoutProps) {
	return <div className={cn("mx-auto w-full max-w-6xl space-y-6", className)} {...props} />;
}
