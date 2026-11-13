import { ChevronDown } from "lucide-react";

import { UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

export interface FiledIssueProps {
	title: string;
	text: string;
}

/** What Hephaestus filed, as it filed it. A maintainer can change the issue on GitHub later. */
export function FiledIssue({ title, text }: FiledIssueProps) {
	return (
		<Collapsible>
			<CollapsibleTrigger render={<Button variant="ghost" size="sm" className="group" />}>
				The issue as filed
				<ChevronDown
					data-icon="inline-end"
					aria-hidden
					className="transition-transform group-data-[panel-open]:rotate-180"
				/>
			</CollapsibleTrigger>
			<CollapsibleContent>
				<div className="mt-2 flex flex-col gap-2 rounded-lg border p-3">
					<p className="font-medium break-words">{title}</p>
					<div className="min-w-0 text-sm break-words">
						<UntrustedMarkdown>{text}</UntrustedMarkdown>
					</div>
				</div>
			</CollapsibleContent>
		</Collapsible>
	);
}
