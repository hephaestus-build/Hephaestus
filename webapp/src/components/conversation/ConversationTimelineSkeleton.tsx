import { cn } from "cn";
import { useSpinDelay } from "spin-delay";

import { Message, MessageContent } from "@/components/ui/message";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * Line widths of each stand-in message, the developer's and Hephaestus's in turn: a share of the text
 * column, capped at what a desktop column shows. A fixed width would overflow the column of a
 * 320 px window, which is about 244 px wide.
 */
const LINES = [
	["w-4/5 max-w-56", "w-2/5 max-w-28"],
	["w-3/5 max-w-40", "w-full max-w-64", "w-1/2 max-w-32"],
	["w-full max-w-72", "w-1/2 max-w-36"],
	["w-full max-w-72", "w-4/5 max-w-52", "w-1/3 max-w-24"],
] as const;

/**
 * A conversation while it loads, in the shape `ConversationTimeline` gives it: left-aligned rows with an
 * avatar, a name line and the words. `rows` comes from the region: the page fills its height, a
 * thread panel shows fewer. `label` is what a screen reader hears loads.
 *
 * It shows only after a second. Most reads come from the cache or arrive sooner, and a skeleton
 * that shows and goes at once reads as a fault. A caller mounts it only while the read is on its
 * way, so it has no minimum time to hold.
 */
export function ConversationTimelineSkeleton({
	rows = 4,
	label = "Loading the conversation…",
}: {
	rows?: number;
	label?: string;
}) {
	// `ssr: false`, or a skeleton that mounts with the read in flight shows at once.
	const shown = useSpinDelay(true, { delay: 1000, minDuration: 0, ssr: false });
	if (!shown) {
		return null;
	}
	return (
		<div aria-busy="true">
			<span className="sr-only">{label}</span>
			<div
				className="mx-auto flex w-full max-w-3xl flex-col gap-6 overflow-hidden px-4 py-6"
				aria-hidden
			>
				{Array.from({ length: rows }, (_, index) => LINES[index % LINES.length] ?? LINES[0]).map(
					(lines, index) => (
						<Message key={index} className="gap-3">
							<Skeleton className="size-8 shrink-0 rounded-full" />
							<MessageContent>
								<Skeleton className="h-3 w-24" />
								{lines.map((width) => (
									<Skeleton key={width} className={cn("h-4", width)} />
								))}
							</MessageContent>
						</Message>
					),
				)}
			</div>
		</div>
	);
}
