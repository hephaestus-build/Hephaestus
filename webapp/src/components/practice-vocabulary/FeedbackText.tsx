import { Fragment } from "react";

import type { GroupRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";

import type { FeedbackTextSegment } from "./feedback-text";
import { getGroupVisual } from "./group-visuals";
import { GroupName } from "./GroupName";
import { PracticePill, type PracticeSquare } from "./PracticePill";

export interface FeedbackTextProps {
	segments: FeedbackTextSegment[];
	onOpenPractice?: (practiceSlug: string) => void;
	/**
	 * The workspace's groups: where a group segment reads its icon and its colour. A group the
	 * list does not carry falls back to the catalog's default folder and grey.
	 */
	groups?: GroupRef[];
	onOpenGroup?: (groupSlug: string) => void;
	/**
	 * The group every practice in the text belongs to, as a table row about one group knows it: its
	 * practices are then named by the row's own label, the gray pill with the group's icon, instead of
	 * the plain pill of running prose.
	 */
	practiceGroup?: PracticeSquare;
	/** A paragraph when the text stands on its own, a span when it sits inside other text. */
	as?: "p" | "span";
	className?: string;
}

const NO_GROUPS: GroupRef[] = [];

/** Punctuation that must stay on the line of the work link before it. */
const LEADING_PUNCTUATION = /^[.,;:!?)]+/u;

/** The punctuation a text segment opens with when a work link comes right before it. */
function punctuationAfterWork(segments: FeedbackTextSegment[], index: number): string | undefined {
	const [segment, next] = [segments[index], segments[index + 1]];
	return segment?.type === "work" && next?.type === "text"
		? LEADING_PUNCTUATION.exec(next.text)?.[0]
		: undefined;
}

/**
 * Running text with its practice names as pills, its groups as their own icon and color, and its
 * work references as links. A practice is the plain gray pill, a button only when there is
 * somewhere to open it; a group is the name the feedback card's head shows, so a group that moved
 * reads as itself rather than as a practice whose pill went missing;
 * a work reference is a link only when it has an address — the provider's page for the work, so it
 * opens in a new tab — and a word otherwise.
 */
export function FeedbackText({
	segments,
	onOpenPractice,
	groups,
	onOpenGroup,
	practiceGroup,
	as: Component = "span",
	className,
}: FeedbackTextProps) {
	return (
		<Component className={className}>
			{segments.map((segment, index) => {
				// A link with its icon is an atomic inline, so a line may break after it, even before a
				// full stop. The punctuation that follows it moves into one unbreakable span with it.
				const held = punctuationAfterWork(segments, index - 1);
				if (held !== undefined && segment.type === "text") {
					return <Fragment key={index}>{segment.text.slice(held.length)}</Fragment>;
				}
				const node = (
					<Segment
						segment={segment}
						onOpenPractice={onOpenPractice}
						groups={groups}
						onOpenGroup={onOpenGroup}
						practiceGroup={practiceGroup}
					/>
				);
				const mark = punctuationAfterWork(segments, index);
				return mark === undefined ? (
					<Fragment key={index}>{node}</Fragment>
				) : (
					<span key={index} className="whitespace-nowrap">
						{node}
						{mark}
					</span>
				);
			})}
		</Component>
	);
}

function Segment({
	segment,
	onOpenPractice,
	groups = NO_GROUPS,
	onOpenGroup,
	practiceGroup,
}: {
	segment: FeedbackTextSegment;
} & Pick<FeedbackTextProps, "onOpenPractice" | "groups" | "onOpenGroup" | "practiceGroup">) {
	if (segment.type === "text") {
		return segment.text;
	}
	if (segment.type === "work") {
		return (
			<InlineLink href={segment.ref.url ?? undefined} external>
				{segment.ref.label}
			</InlineLink>
		);
	}
	if (segment.type === "group") {
		const group = groups.find((candidate) => candidate.slug === segment.slug);
		const { Icon, pill } = getGroupVisual(group?.icon, group?.color);
		return (
			<GroupName
				name={segment.name}
				icon={Icon}
				pill={pill}
				onOpen={onOpenGroup && (() => onOpenGroup(segment.slug))}
				className="align-baseline"
			/>
		);
	}
	return (
		<PracticePill
			name={segment.name}
			onOpen={onOpenPractice && (() => onOpenPractice(segment.slug))}
			group={practiceGroup}
			size={practiceGroup === undefined ? "sm" : "row"}
			// A pill is taller than the line: a little margin keeps pills on wrapped lines apart.
			className={practiceGroup === undefined ? "align-baseline" : "my-0.5 align-middle"}
		/>
	);
}
