import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

/** The full-height surface the sign-in and setup screens sit on, with the product's brand wash. */
export function AuthSurface({ className, children }: { className?: string; children: ReactNode }) {
	return (
		<div className={cn("relative isolate min-h-svh overflow-hidden bg-background", className)}>
			<div
				aria-hidden="true"
				className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(70rem_40rem_at_50%_-10rem,color-mix(in_oklab,var(--color-mentor)_14%,transparent),transparent_65%)]"
			/>
			{children}
		</div>
	);
}
