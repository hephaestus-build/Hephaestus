import type { PracticeGuide, PracticeVisual as PracticeVisualValue } from "@/api/types.gen";
import { PracticeVisual } from "@/components/practice-guidance/PracticeVisual";

import { figureDescription } from "./practice-guidance-draft";

/** One version of a visual, for an admin comparing versions: the picture, then its description. */
export function PracticeVisualVersion({ visual }: { visual: PracticeVisualValue }) {
	return (
		<div className="flex max-w-md flex-col gap-1">
			<PracticeVisual svg={visual.svg} alt={visual.alt} />
			{/* The picture's name already says it, so a screen reader hears it once. */}
			<p aria-hidden className="text-xs text-muted-foreground">
				{visual.alt}
			</p>
		</div>
	);
}

/**
 * One version of a guide, for an admin comparing versions: the Markdown as written, then its
 * figures. Rendered, the guide's headings would break the outline of the page around it.
 */
export function PracticeGuideVersion({ guide }: { guide: PracticeGuide }) {
	const figures = Object.keys(guide.figures);
	return (
		<div className="flex max-w-md flex-col gap-2">
			<pre
				// oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- Keyboard users must be able to scroll this region.
				tabIndex={0}
				className="max-h-48 overflow-auto font-sans text-xs break-words whitespace-pre-wrap"
			>
				{guide.markdown}
			</pre>
			{figures.length > 0 && (
				<ul aria-label="Figures" className="flex flex-col gap-2">
					{figures.map((name) => (
						<li key={name} className="flex flex-col gap-1">
							<PracticeVisual
								svg={guide.figures[name] ?? ""}
								alt={figureDescription(guide.markdown, name) ?? `Figure ${name}`}
							/>
							<p className="font-mono text-xs text-muted-foreground">figures/{name}.svg</p>
						</li>
					))}
				</ul>
			)}
		</div>
	);
}
