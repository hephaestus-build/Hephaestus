import { InfoIcon, type LucideIcon } from "lucide-react";
import { type ReactNode, useId } from "react";

import { cn } from "cn";
import { FOCUS_RING } from "@/components/common/focus";
import { TipPopover } from "@/components/common/TipPopover";

export interface InfoTipProps {
	/** Names the button and the explanation it opens, and heads the panel: "About your figures". */
	label: string;
	/** The explanation the page keeps out of its running text, best as {@link InfoLines}. */
	children: ReactNode;
	className?: string;
}

/** One line of an (i) panel: its key term, in bold, then what it says; a second term may follow. */
export interface InfoLine {
	term: string;
	text: string;
	second?: { term: string; text: string };
	/** A colour sample in front of the line, for a legend: a text colour the sample paints with. */
	swatchClass?: string;
	/** The icon of the control the line explains, in front of it, so the reader matches the two. */
	icon?: LucideIcon;
}

/**
 * The lines of an (i) panel as short paragraphs, each led by its key term in bold, so the panel reads
 * at a glance and not as one block. A legend line starts with its colour, which its words also name;
 * a line about a control starts with the control's icon, which its term names.
 */
export function InfoLines({ lines }: { lines: readonly InfoLine[] }) {
	return lines.map(({ icon: Icon, ...line }) => (
		<p key={line.term} className="flex gap-2">
			{Icon !== undefined && (
				<Icon aria-hidden className="mt-1 size-3.5 shrink-0 text-foreground" />
			)}
			{line.swatchClass !== undefined && (
				<span
					aria-hidden
					className={cn("mt-1.5 h-2 w-4 shrink-0 rounded-sm bg-current", line.swatchClass)}
				/>
			)}
			<span>
				<strong className="font-semibold text-foreground">{line.term}</strong> {line.text}
				{line.second && (
					<>
						{" "}
						<strong className="font-semibold text-foreground">{line.second.term}</strong>{" "}
						{line.second.text}
					</>
				)}
			</span>
		</p>
	));
}

/** Lines of an (i) panel under one heading, for a panel that answers more than one question. */
export interface InfoGroup {
	heading: string;
	lines: readonly InfoLine[];
}

/**
 * {@link InfoLines} in groups, each under its heading in bold, so a long panel reads as a few short
 * answers. Each group is a labelled group, so a screen reader hears where one ends.
 */
export function InfoGroups({ groups }: { groups: readonly InfoGroup[] }) {
	const id = useId();
	return groups.map((group, index) => {
		const headingId = `${id}-${index}`;
		return (
			<div
				key={group.heading}
				role="group"
				aria-labelledby={headingId}
				className="flex flex-col gap-1.5 pt-1"
			>
				<p id={headingId} className="font-semibold text-foreground">
					{group.heading}
				</p>
				<InfoLines lines={group.lines} />
			</div>
		);
	});
}

/**
 * An (i) button whose explanation opens on hover, on keyboard focus, and on a tap or a press, so a
 * pointer, a keyboard and a touch screen all reach it ({@link TipPopover}). The trigger is
 * `StatusIcon`'s: a chromeless button with a 24 px target and the focus ring.
 */
export function InfoTip({ label, children, className }: InfoTipProps) {
	return (
		<TipPopover
			label={label}
			align="start"
			trigger={
				<button
					type="button"
					aria-label={label}
					className={cn(
						// 24 px drawn, as WCAG 2.2 SC 2.5.8 asks, and not widened by a pseudo-element: the
						// legend's (i) ends its row, where a wider target would push the page sideways.
						"-my-1.5 inline-flex size-6 shrink-0 cursor-help items-center justify-center rounded-full align-middle text-muted-foreground hover:text-foreground data-popup-open:text-foreground",
						FOCUS_RING,
						className,
					)}
				/>
			}
			// About 38 characters a line, roomy, in the popover's own colours, so dark mode reads too.
			wide
			content={
				<div className="flex flex-col gap-2 leading-relaxed text-muted-foreground">
					<p className="font-semibold text-foreground">{label}</p>
					{children}
				</div>
			}
		>
			<InfoIcon aria-hidden className="size-3.5" />
		</TipPopover>
	);
}
