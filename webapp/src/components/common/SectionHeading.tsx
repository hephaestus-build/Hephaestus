import type { ReactNode } from "react";

export interface SectionHeadingProps {
	children: ReactNode;
}

export function SectionHeading({ children }: SectionHeadingProps) {
	return <h3 className="mb-1 text-xs font-semibold text-muted-foreground uppercase">{children}</h3>;
}
