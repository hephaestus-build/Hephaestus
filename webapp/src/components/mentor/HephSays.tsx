import type { ReactNode } from "react";

import { HephIcon } from "@/components/brand/HephIcon";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Message, MessageContent } from "@/components/ui/message";

/**
 * Heph speaking from a bubble. `intro` is fixed for the page; `narration` is the page's narration
 * line, announced as it changes, so every other line that follows the reader's answers is plain text.
 */
export function HephSays({ intro, narration }: { intro: ReactNode; narration: string }) {
	return (
		<Message className="items-start gap-3">
			<HephIcon className="shrink-0" size={64} pad={2} />
			<MessageContent>
				<Bubble variant="outline" className="w-full max-w-full">
					<BubbleContent className="w-full">
						<p className="sr-only">Heph says:</p>
						<p>{intro}</p>
						<p aria-live="polite" className="mt-2 font-medium">
							{narration}
						</p>
					</BubbleContent>
				</Bubble>
			</MessageContent>
		</Message>
	);
}
