import { useId } from "react";

import type { PracticeVisual as PracticeVisualValue } from "@/api/types.gen";
import { hasText } from "@/lib/text";

import { PracticeVisual } from "./PracticeVisual";

export interface PracticeFigureProps {
	visual: PracticeVisualValue;
	/** What good looks like, set directly under the picture. */
	caption?: string;
}

/**
 * A practice's picture with its caption directly under it, named by the caption when it has one.
 * No surface: it sits on the panel's ground at the width of the text around it.
 */
export function PracticeFigure({ visual, caption }: PracticeFigureProps) {
	const captionId = useId();
	return (
		<figure
			className="flex flex-col gap-2"
			// Named explicitly: not every browser names a figure from its figcaption.
			aria-labelledby={hasText(caption) ? captionId : undefined}
		>
			<PracticeVisual svg={visual.svg} alt={visual.alt} />
			{hasText(caption) && (
				<figcaption id={captionId} className="text-sm text-pretty text-muted-foreground">
					{caption}
				</figcaption>
			)}
		</figure>
	);
}
