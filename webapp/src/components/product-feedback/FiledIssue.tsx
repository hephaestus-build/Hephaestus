import { ChevronDown } from "lucide-react";
import { lazy, Suspense } from "react";
import { useSpinDelay } from "spin-delay";

import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";

const UntrustedMarkdown = lazy(async () => {
	const markdown = await import("@/components/common/UntrustedMarkdown");
	return { default: markdown.UntrustedMarkdown };
});

function FiledIssueLoading() {
	const visible = useSpinDelay(true, { delay: 1000, minDuration: 0 });
	return visible ? (
		<div aria-busy="true" className="flex flex-col gap-2">
			<Skeleton className="h-4 w-3/4" />
			<Skeleton className="h-4 w-full" />
			<Skeleton className="h-4 w-1/2" />
		</div>
	) : null;
}

export interface FiledIssueProps {
	title: string;
	text: string;
}

/** What Hephaestus filed, as it filed it. A maintainer can change the issue on GitHub later. */
export function FiledIssue({ title, text }: FiledIssueProps) {
	return (
		<Collapsible>
			<CollapsibleTrigger
				aria-label={`The issue as filed: ${title}`}
				render={<Button variant="ghost" size="sm" className="group" />}
			>
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
						<Suspense fallback={<FiledIssueLoading />}>
							<UntrustedMarkdown>{text}</UntrustedMarkdown>
						</Suspense>
					</div>
				</div>
			</CollapsibleContent>
		</Collapsible>
	);
}
