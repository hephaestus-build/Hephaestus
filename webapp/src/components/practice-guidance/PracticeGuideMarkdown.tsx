import { cn } from "cn";
import type { PracticeGuide } from "@/api/types.gen";
import {
	UNTRUSTED_MARKDOWN_PROSE,
	UntrustedMarkdown,
	type UntrustedMarkdownImage,
} from "@/components/common/UntrustedMarkdown";

import { guideFigure } from "./practice-svg";
import { PRACTICE_VISUAL_WIDTH, PracticeVisual } from "./PracticeVisual";

export interface PracticeGuideMarkdownProps {
	/** The guide: its Markdown and the SVG figures the Markdown shows by name. */
	guide: PracticeGuide;
	/** The level of the guide's own headings, one below the label it sits under. */
	headingLevel?: 4 | 5;
}

/**
 * A practice guide, in the same restricted Markdown as feedback. The one image form it draws is the
 * guide's own figure, `![alt](figures/<name>.svg)`, as a themed picture named by its alt text, bare
 * on the prose's ground at the width the introduction gives its picture. Any other image source, and a figure the guide does not
 * carry, draws nothing, so the guide loads nothing from elsewhere.
 */
export function PracticeGuideMarkdown({ guide, headingLevel = 4 }: PracticeGuideMarkdownProps) {
	const renderFigure = ({ src, alt }: UntrustedMarkdownImage) => {
		const svg = guideFigure(guide.figures, src);
		return svg === undefined ? null : (
			// The guide's body is `text-sm`, so its figures draw at the introduction's width.
			<figure>
				<PracticeVisual svg={svg} alt={alt} className={PRACTICE_VISUAL_WIDTH} />
			</figure>
		);
	};
	return (
		// A figure takes the paragraphs' `my-2`: a practice SVG draws to its canvas edges and has no margin.
		<div
			// Bullet markers in the muted foreground, as the bullets under a practice in the profile's
			// tables are; the prose default is too light to find a line by.
			className={cn(
				UNTRUSTED_MARKDOWN_PROSE,
				"max-w-2xl text-sm text-pretty prose-figure:my-2 [&_ul>li]:marker:text-muted-foreground",
			)}
		>
			<UntrustedMarkdown renderImage={renderFigure} headingLevel={headingLevel}>
				{guide.markdown}
			</UntrustedMarkdown>
		</div>
	);
}
