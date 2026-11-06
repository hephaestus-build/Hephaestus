import { cva, type VariantProps } from "class-variance-authority";
import { ChevronRight } from "lucide-react";
import { type ComponentProps, type ReactElement, type ReactNode, useId } from "react";

import { cn } from "cn";
import { FOCUS_RING } from "@/components/common/focus";
import { rendersContent } from "@/lib/react-node";

import { useSectionOpen } from "./section-folds";

/** The section title classes, for a heading that names a group without being a region of its own. */
export const sectionTitleVariants = cva("text-foreground", {
	variants: {
		size: {
			/** A subsection inside a panel or a page region. */
			sm: "text-sm font-medium",
			/** A section of an admin page, one step below the page title. */
			md: "text-lg font-semibold",
			/** A section of a practice surface, where the page title is `text-2xl` and the levels' titles match it. */
			lg: "text-xl font-semibold",
		},
	},
	defaultVariants: { size: "md" },
});

export interface SectionProps
	extends Omit<ComponentProps<"section">, "title">, VariantProps<typeof sectionTitleVariants> {
	title: ReactNode;
	description?: ReactNode;
	/** An (i) beside the title, outside the heading, for what the section is for (an `InfoTip`). */
	info?: ReactNode;
	actions?: ReactElement | undefined;
	/** `h3` when the section sits inside another one, `h4` one step further, so the outline stays truthful. */
	level?: 2 | 3 | 4;
	/**
	 * Base for `${id}-title` and `${id}-description`, for a nested control that has to be named or
	 * described by them — a `role="radiogroup"` is not named by an enclosing heading.
	 */
	id?: string;
	/**
	 * Lets the reader fold the section away: the heading becomes a button and the choice is kept in
	 * this browser under this key, one per page and section ("practice-reviews-overview:queue").
	 * Only for admin pages; a developer's practice surface never hides its feedback.
	 */
	foldKey?: string;
}

const HEADINGS = { 2: "h2", 3: "h3", 4: "h4" } as const;

/**
 * Configured rather than composed: the three holes always take the same kinds of thing, and a
 * compound API would publish three ReactNode rows no Storybook control can drive.
 */
export function Section({
	title,
	description,
	info,
	actions,
	level = 2,
	size,
	className,
	id,
	foldKey,
	children,
	...props
}: SectionProps) {
	const generatedId = useId();
	const base = id ?? generatedId;
	const Heading = HEADINGS[level];
	const [open, setOpen] = useSectionOpen(foldKey);

	return (
		<section
			id={id}
			aria-labelledby={`${base}-title`}
			className={cn("space-y-3", className)}
			{...props}
		>
			<div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
				<div className="min-w-0 space-y-1">
					<div className="flex items-center gap-1">
						<Heading
							id={`${base}-title`}
							className={cn(sectionTitleVariants({ size }), "break-words")}
						>
							{foldKey === undefined ? (
								title
							) : (
								// The heading keeps its level; the button inside it folds what the section holds.
								<button
									type="button"
									aria-expanded={open}
									aria-controls={`${base}-content`}
									onClick={() => setOpen(!open)}
									className={cn(
										"inline-flex items-center gap-1.5 rounded-sm text-left",
										FOCUS_RING,
									)}
								>
									{title}
									<ChevronRight
										aria-hidden
										className={cn(
											"size-4 shrink-0 text-muted-foreground transition-transform",
											open && "rotate-90",
										)}
									/>
								</button>
							)}
						</Heading>
						{info}
					</div>
					{rendersContent(description) && (
						<p id={`${base}-description`} className="max-w-2xl text-sm text-muted-foreground">
							{description}
						</p>
					)}
				</div>
				{actions}
			</div>
			{foldKey === undefined ? (
				children
			) : (
				<div id={`${base}-content`} hidden={!open} className="space-y-3">
					{children}
				</div>
			)}
		</section>
	);
}
