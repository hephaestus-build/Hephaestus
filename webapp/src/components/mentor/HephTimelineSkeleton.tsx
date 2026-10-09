import { cn } from "cn";
import { Message, MessageContent } from "@/components/ui/message";
import { Skeleton } from "@/components/ui/skeleton";

/** Line widths of each stand-in message, the developer's and Heph's in turn. */
const LINES = [
	["w-56", "w-28"],
	["w-40", "w-64", "w-32"],
	["w-72", "w-36"],
	["w-72", "w-52", "w-24"],
] as const;

/**
 * A conversation while it loads, in the shape `HephTimeline` gives it: left-aligned rows with an
 * avatar, a name line and the words. `rows` comes from the region: the page fills its height, a
 * thread panel shows fewer.
 */
export function HephTimelineSkeleton({ rows = 4 }: { rows?: number }) {
	return (
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
	);
}
