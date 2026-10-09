import { cn } from "cn";

import { sanitizePracticeSvg, usesThemeClasses } from "./practice-svg";

/** The `pv-*` classes as theme tokens; `docs/admin/writing-practices.mdx` names them for authors. */
const PRACTICE_SVG_THEME = [
	"[&_text]:font-sans",
	"[&_.pv-fill-ink]:fill-foreground",
	"[&_.pv-fill-muted]:fill-muted-foreground",
	"[&_.pv-fill-surface]:fill-card",
	"[&_.pv-fill-accent]:fill-mentor",
	"[&_.pv-fill-accent-soft]:fill-mentor/15",
	"[&_.pv-stroke-ink]:stroke-foreground",
	"[&_.pv-stroke-muted]:stroke-muted-foreground",
	"[&_.pv-stroke-line]:stroke-border",
	"[&_.pv-stroke-accent]:stroke-mentor",
];

export interface PracticeVisualProps {
	/** The SVG markup, as the server stores it. It is sanitized again before it is drawn. */
	svg: string;
	/** What the picture shows, for people who cannot see it; the picture's accessible name. */
	alt: string;
	/** Sizes the picture in its caller's layout; the picture fills the width it is given. */
	className?: string;
}

/**
 * One practice picture, drawn inline so its `pv-*` classes follow the theme. It brings no surface of
 * its own: it sits on whatever ground its caller gives it, so a caller's card is never doubled. A
 * picture with none of the classes brings its own colors, so it alone keeps a light ground in both
 * themes. The element is the image, so the reader hears `alt` once and not the words inside it.
 */
export function PracticeVisual({ svg, alt, className }: PracticeVisualProps) {
	const markup = sanitizePracticeSvg(svg);
	return (
		<div
			role="img"
			aria-label={alt}
			className={cn(
				"[&_svg]:block [&_svg]:h-auto [&_svg]:w-full",
				PRACTICE_SVG_THEME,
				!usesThemeClasses(markup) && "light rounded-md bg-background p-2",
				className,
			)}
			// oxlint-disable-next-line react/no-danger -- sanitizePracticeSvg output; inline, not an <img>, so the pv-* classes take the theme
			dangerouslySetInnerHTML={{ __html: markup }}
		/>
	);
}
