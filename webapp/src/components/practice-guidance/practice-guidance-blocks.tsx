import { ChevronDownIcon } from "lucide-react";
import { useId } from "react";

import { cn } from "cn";
import type { PracticeGuide, PracticeVisual as PracticeVisualValue } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { hasText } from "@/lib/text";

import { PracticeGuideMarkdown } from "./PracticeGuideMarkdown";
import { PracticeVisual } from "./PracticeVisual";

/** A practice visual as a figure, named by its caption when it has one. */
export function PracticeFigure({
	visual,
	caption,
	className,
}: {
	visual: PracticeVisualValue;
	caption?: string;
	className?: string;
}) {
	const captionId = useId();
	return (
		<figure
			className={cn("flex flex-col gap-2", className)}
			// Named explicitly: not every browser names a figure from its figcaption.
			aria-labelledby={hasText(caption) ? captionId : undefined}
		>
			<PracticeVisual svg={visual.svg} alt={visual.alt} />
			{hasText(caption) && (
				<figcaption id={captionId} className="max-w-2xl text-sm text-pretty text-muted-foreground">
					{caption}
				</figcaption>
			)}
		</figure>
	);
}

/** The practice guide, closed behind "Read more" until the reader opens it. */
export function PracticeGuideDisclosure({ guide }: { guide: PracticeGuide }) {
	return (
		<Collapsible>
			<CollapsibleTrigger render={<Button variant="outline" size="sm" className="group" />}>
				Read more
				<ChevronDownIcon
					data-icon="inline-end"
					className="transition-transform group-aria-expanded:rotate-180"
					aria-hidden
				/>
			</CollapsibleTrigger>
			<CollapsibleContent>
				<PracticeGuideMarkdown guide={guide} className="mt-3" />
			</CollapsibleContent>
		</Collapsible>
	);
}
