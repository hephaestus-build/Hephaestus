import { ChevronDownIcon } from "lucide-react";
import type { ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { formatDateTime } from "@/lib/dates";

/**
 * The answer's words, shortened to its opening and its close, so that each disclosure on the page has its own
 * accessible name: two answers that start alike usually end differently.
 */
function excerpt(text: string): string {
	const words = text.replaceAll(/\s+/gu, " ").trim();
	if (words.length <= 96) {
		return words;
	}
	return `${words.slice(0, 47).trimEnd()} … ${words.slice(-40).trimStart()}`;
}

export interface AnswerUseDisclosureProps {
	/** When the answer was written, to the second. */
	answeredAt: Date;
	/** The answer's words; their opening and close tell this disclosure apart from the others on the page. */
	text: string;
	/** The groups of what the answer used, from rows. */
	children: ReactNode;
}

/**
 * The closed "What Hephaestus used" disclosure under an answer (ADR 0157 decision 8). Each kind of conversation fills
 * it from its own rows, so the private and the team conversation show one control.
 */
export function AnswerUseDisclosure({ answeredAt, text, children }: AnswerUseDisclosureProps) {
	return (
		<Collapsible>
			<CollapsibleTrigger
				aria-label={`What Hephaestus used (Hephaestus, ${formatDateTime(answeredAt)}: ${excerpt(text)})`}
				render={
					<Button variant="ghost" size="xs" className="group -ml-2 w-fit text-muted-foreground" />
				}
			>
				What Hephaestus used
				<ChevronDownIcon
					data-icon="inline-end"
					aria-hidden
					className="transition-transform group-data-[panel-open]:rotate-180"
				/>
			</CollapsibleTrigger>
			<CollapsibleContent>
				<div className="mt-1 flex flex-col gap-3 rounded-lg border p-3 text-sm">{children}</div>
			</CollapsibleContent>
		</Collapsible>
	);
}

export function UseGroup({ title, items }: { title: string; items: readonly string[] }) {
	return (
		<section className="flex flex-col gap-1" aria-label={title}>
			<p className="font-medium text-foreground">{title}</p>
			<ul className="list-disc pl-5 text-muted-foreground">
				{items.map((item) => (
					// The server lists each kind of source once, so its words are unique in a group.
					<li key={item} className="break-words">
						{item}
					</li>
				))}
			</ul>
		</section>
	);
}
