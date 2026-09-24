import type { PracticeGroup } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";

import type { FeedbackTextSegment } from "./feedback-text";

export interface FeedbackTextProps {
	segments: FeedbackTextSegment[];
	onOpenPractice?: (practiceSlug: string) => void;
	/**
	 * The workspace's groups: where a group segment reads its icon and its colour. A group the
	 * list does not carry falls back to the catalog's default folder and grey.
	 */
	groups?: PracticeGroup[];
	onOpenGroup?: (groupSlug: string) => void;
	/** A paragraph when the text stands on its own, a span when it sits inside other text. */
	as?: "p" | "span";
	className?: string;
}

/**
 * Running text with its practice names as pills, its groups as their own icon and colour, and its
 * work references as links. A practice is the one grey pill every practice surface names it with,
 * a button only when there is somewhere to open it; a group is the name the feedback card's head
 * shows, so a group that moved reads as itself rather than as a practice whose pill went missing;
 * a work reference is a link only when it has an address — the provider's page for the work, so it
 * opens in a new tab — and a word otherwise.
 */
const NO_GROUPS: PracticeGroup[] = [];

export function FeedbackText({
	segments,
	onOpenPractice,
	groups,
	onOpenGroup,
	as: Component = "span",
	className,
}: FeedbackTextProps) {
	return (
		<Component className={className}>
			{segments.map((segment, index) => (
				<Segment
					key={index}
					segment={segment}
					onOpenPractice={onOpenPractice}
					groups={groups}
					onOpenGroup={onOpenGroup}
				/>
			))}
		</Component>
	);
}

function Segment({
	segment,
	onOpenPractice,
	groups = NO_GROUPS,
	onOpenGroup,
}: {
	segment: FeedbackTextSegment;
} & Pick<FeedbackTextProps, "onOpenPractice" | "groups" | "onOpenGroup">) {
	if (segment.type === "text") {
		return segment.text;
	}
	if (segment.type === "work") {
		return (
			<InlineLink href={segment.ref.url} external>
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
			className="align-baseline"
		/>
	);
}
