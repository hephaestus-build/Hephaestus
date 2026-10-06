import { MessageSquareTextIcon } from "lucide-react";
import { type ReactElement, type ReactNode, useId } from "react";

import { SectionLabel } from "@/components/common/SectionLabel";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";

export interface FeedbackEmptyProps {
	/** What is missing. */
	title?: string;
	/** When it will show up. */
	description?: string;
	/** The way to what is there instead. */
	action?: ReactElement;
}

/**
 * A list of feedback with nothing in it, in the shape every practice surface says it in: the title
 * names what is missing and the sentence says when it will show up. Feedback posted on the work
 * stays there; these lists hold feedback about recurring patterns.
 */
export function FeedbackEmpty({
	title = "No feedback yet",
	description = "Feedback appears here once a review sees the same pattern in your work more than once. Feedback posted on the work stays there.",
	action,
}: FeedbackEmptyProps) {
	return (
		<Empty>
			<EmptyHeader>
				<EmptyMedia variant="icon">
					<MessageSquareTextIcon />
				</EmptyMedia>
				<EmptyTitle>{title}</EmptyTitle>
				<EmptyDescription>{description}</EmptyDescription>
			</EmptyHeader>
			{action && <EmptyContent>{action}</EmptyContent>}
		</Empty>
	);
}

/** A level opened for a group its page does not list, such as a stale address. */
export function NoSuchGroup() {
	return (
		<Empty variant="outlined">
			<EmptyHeader>
				<EmptyTitle>We could not find this practice group</EmptyTitle>
				<EmptyDescription>
					Check the link, or ask a workspace admin whether it is active in this workspace.
				</EmptyDescription>
			</EmptyHeader>
		</Empty>
	);
}

/** A practice or a group the catalog has no words for. */
export function NoDescription() {
	return <p className="text-sm text-muted-foreground">No description yet.</p>;
}

export interface LabelledBlockProps {
	label: string;
	/** `h3` inside a section that has its own heading. */
	as?: "h2" | "h3";
	className?: string;
	children: ReactNode;
}

/** A block under its small label, named by it. */
export function LabelledBlock({ label, as = "h2", className, children }: LabelledBlockProps) {
	const id = useId();
	return (
		<section className={className} aria-labelledby={id}>
			<SectionLabel as={as} id={id}>
				{label}
			</SectionLabel>
			{children}
		</section>
	);
}
