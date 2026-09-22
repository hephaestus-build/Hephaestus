import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

export interface Fact {
	icon: LucideIcon;
	term: string;
	detail: ReactNode;
}

/**
 * Each fact is one group: an icon chip, the term beside it, and the sentence under the term.
 * Grouping beats a two-column table here because a reader takes facts one at a time, and a chip
 * marks where each one starts. The chip lives inside the `dt`, so the `dl` holds nothing but
 * term and detail pairs.
 */
export function FactList({ facts }: { facts: readonly Fact[] }) {
	return (
		<dl className="grid gap-4">
			{facts.map(({ icon: Icon, term, detail }) => (
				<div key={term} className="grid grid-cols-[2rem_1fr] gap-x-3 gap-y-0.5">
					<dt className="col-span-2 flex items-center gap-3 text-sm font-medium">
						<span
							aria-hidden="true"
							className="inline-flex size-8 shrink-0 items-center justify-center rounded-md bg-mentor/10 text-mentor [&_svg]:size-4"
						>
							<Icon />
						</span>
						{term}
					</dt>
					<dd className="col-start-2 text-sm leading-relaxed text-muted-foreground">{detail}</dd>
				</div>
			))}
		</dl>
	);
}
