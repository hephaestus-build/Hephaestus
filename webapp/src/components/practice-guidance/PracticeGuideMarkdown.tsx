import { cn } from "cn";
import type { PracticeGuide } from "@/api/types.gen";
import {
	UNTRUSTED_MARKDOWN_PROSE,
	UntrustedMarkdown,
	type UntrustedMarkdownImage,
} from "@/components/common/UntrustedMarkdown";

import { guideFigure } from "./practice-svg";
import { PracticeVisual } from "./PracticeVisual";

export interface PracticeGuideMarkdownProps {
	/** The guide: its Markdown and the SVG figures the Markdown shows by name. */
	guide: PracticeGuide;
	className?: string;
}

/**
 * A practice's "Read more" guide, in the same restricted Markdown as feedback. The one image form
 * it draws is the guide's own figure, `![alt](figures/<name>.svg)`, as a themed picture named by
 * its alt text. Any other image source, and a figure the guide does not carry, draws nothing, so
 * the guide loads nothing from elsewhere.
 */
export function PracticeGuideMarkdown({ guide, className }: PracticeGuideMarkdownProps) {
	const renderFigure = ({ src, alt }: UntrustedMarkdownImage) => {
		const svg = guideFigure(guide.figures, src);
		return svg === undefined ? null : (
			<figure className="my-4">
				<PracticeVisual svg={svg} alt={alt} />
			</figure>
		);
	};
	return (
		<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "max-w-2xl text-sm text-pretty", className)}>
			<UntrustedMarkdown renderImage={renderFigure}>{guide.markdown}</UntrustedMarkdown>
		</div>
	);
}
