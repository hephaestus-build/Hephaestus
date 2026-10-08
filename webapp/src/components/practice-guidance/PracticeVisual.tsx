import { cva, type VariantProps } from "class-variance-authority";

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

const practiceVisualVariants = cva("rounded-xl border bg-card", {
	variants: {
		size: {
			/** A thumbnail or one version beside another, in an admin list or comparison. */
			sm: "p-2",
			/** The picture a developer reads. */
			md: "p-4 sm:p-6",
		},
	},
	defaultVariants: { size: "md" },
});

export interface PracticeVisualProps extends VariantProps<typeof practiceVisualVariants> {
	/** The SVG markup, as the server stores it. It is sanitized again before it is drawn. */
	svg: string;
	/** What the picture shows, for people who cannot see it; the picture's accessible name. */
	alt: string;
	className?: string;
}

/**
 * One practice picture, drawn inline so its `pv-*` classes follow the theme. A picture with none
 * of them brings its own colors, so it sits on the light theme's ground in both themes. The
 * wrapper is the image, so the reader hears `alt` once and not the words inside the picture.
 */
export function PracticeVisual({ svg, alt, size, className }: PracticeVisualProps) {
	const markup = sanitizePracticeSvg(svg);
	return (
		<div role="img" aria-label={alt} className={cn(practiceVisualVariants({ size }), className)}>
			<div
				className={cn(
					"[&_svg]:mx-auto [&_svg]:block [&_svg]:h-auto [&_svg]:max-h-72 [&_svg]:w-full",
					PRACTICE_SVG_THEME,
					!usesThemeClasses(markup) && "light rounded-md bg-background p-2",
				)}
				// oxlint-disable-next-line react/no-danger -- sanitizePracticeSvg output; inline, not an <img>, so the pv-* classes take the theme
				dangerouslySetInnerHTML={{ __html: markup }}
			/>
		</div>
	);
}
