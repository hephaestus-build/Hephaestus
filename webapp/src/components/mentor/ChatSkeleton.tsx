import { cn } from "cn";
import { Message, MessageContent } from "@/components/ui/message";
import { Skeleton } from "@/components/ui/skeleton";

/** Line widths of each stand-in turn, the reader's and Heph's in alternation. */
const TURNS = [
	{ align: "end", lines: ["w-56", "w-28"] },
	{ align: "start", lines: ["w-40", "w-64", "w-32"] },
	{ align: "end", lines: ["w-72", "w-36"] },
	{ align: "start", lines: ["w-72", "w-52", "w-24"] },
] as const;

/** A conversation while it loads, in the shape `Chat` gives it: turns above, the composer below. */
export function ChatSkeleton() {
	return (
		<div className="flex h-full min-h-0 flex-col" aria-hidden>
			<div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 overflow-hidden px-4 py-6">
				{TURNS.map((turn, index) => (
					<Message key={index} align={turn.align}>
						<MessageContent>
							{turn.align === "start" && <Skeleton className="h-4 w-16" />}
							{turn.lines.map((width) => (
								<Skeleton key={width} className={cn("h-4", width)} />
							))}
						</MessageContent>
					</Message>
				))}
			</div>
			<div className="mx-auto flex w-full max-w-3xl flex-col items-center gap-2 px-4 pb-2">
				<Skeleton className="h-24 w-full rounded-lg" />
				<Skeleton className="h-3 w-64" />
			</div>
		</div>
	);
}
