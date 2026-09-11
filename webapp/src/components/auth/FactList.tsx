import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

export interface Fact {
	icon: LucideIcon;
	term: string;
	detail: ReactNode;
}

export function FactList({ facts }: { facts: readonly Fact[] }) {
	return (
		<dl className="grid gap-4 sm:grid-cols-3">
			{facts.map(({ icon: Icon, term, detail }) => (
				<div key={term} className="space-y-1">
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
