import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

export interface Fact {
	icon: LucideIcon;
	term: string;
	detail: ReactNode;
}

/** Facts are text, so they read as rows: the term on the left, its sentence beside it. */
export function FactList({ facts }: { facts: readonly Fact[] }) {
	return (
		<dl className="grid gap-4">
			{facts.map(({ icon: Icon, term, detail }) => (
				<div key={term} className="grid gap-1 sm:grid-cols-[11rem_1fr] sm:gap-4">
					<dt className="flex items-center gap-2 text-sm font-medium">
						<Icon className="size-4 shrink-0 text-mentor" aria-hidden="true" />
						{term}
					</dt>
					<dd className="text-sm leading-relaxed text-muted-foreground">{detail}</dd>
				</div>
			))}
		</dl>
	);
}
