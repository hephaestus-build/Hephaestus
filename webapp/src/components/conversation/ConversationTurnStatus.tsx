import { useEffect, useState } from "react";

import type { ConversationTurn } from "@/api/types.gen";
import { HephaestusMark } from "@/components/brand/HephaestusMark";
import { Button } from "@/components/ui/button";
import { Marker, MarkerContent, MarkerIcon } from "@/components/ui/marker";
import { conversationLabel, conversationMessage } from "@/lib/conversation-copy";
import { hasText } from "@/lib/text";
import { useSpinDelay } from "@/lib/use-spin-delay";

import { ConversationMessage } from "./ConversationMessage";

type ShownTurn = Pick<ConversationTurn, "status" | "surface" | "partial" | "waitingFor">;

/** What the status line says while a turn runs, or while it `waiting` to start. */
function turnStatusLabel(turn: ShownTurn, waiting: boolean): string {
	if (waiting) {
		return turn.waitingFor === "CAPACITY"
			? conversationMessage("waitForCapacity")
			: conversationMessage("waitingToStart");
	}
	if (turn.surface === "SLACK") {
		return conversationMessage("answeringInSlack");
	}
	// The host words the stage from the tool in flight (`partial.stage`); the app shows it as it is.
	if (turn.partial === undefined) {
		return conversationMessage("conversation.stage.thinking");
	}
	if (turn.partial.activity === "WRITING") {
		return conversationMessage("writing");
	}
	const { stage, stageLabel } = turn.partial;
	return stageLabel === undefined ? stage : conversationLabel(stageLabel);
}

/** From this many seconds, the line counts the wait and says the developer can leave. */
const LEAVE_NOTE_AFTER_SECONDS = 10;

export const LEAVE_NOTE = conversationMessage("leaveNote");

/**
 * Whole seconds since `since`, once they reach {@link LEAVE_NOTE_AFTER_SECONDS}; before that, and
 * for a turn asked in Slack, whose answer goes there, nothing. No estimate exists, so nothing
 * shows progress either.
 */
export function useWaitedSeconds(
	turn: Pick<ConversationTurn, "createdAt" | "surface"> | undefined,
): number | undefined {
	const [now, setNow] = useState<Date>();
	const counting = turn !== undefined && turn.surface !== "SLACK";
	useEffect(() => {
		if (!counting) {
			return;
		}
		const tick = () => setNow(new Date());
		const first = setTimeout(tick, 0);
		const every = setInterval(tick, 1000);
		return () => {
			clearTimeout(first);
			clearInterval(every);
		};
	}, [counting]);
	if (!counting || now === undefined) {
		return undefined;
	}
	const seconds = Math.floor((now.getTime() - turn.createdAt.getTime()) / 1000);
	return seconds >= LEAVE_NOTE_AFTER_SECONDS ? seconds : undefined;
}

function formatWaited(seconds: number): string {
	return seconds < 60
		? conversationMessage("waitedSeconds", { seconds })
		: conversationMessage("waitedMinutes", {
				minutes: Math.floor(seconds / 60),
				seconds: seconds % 60,
			});
}

/**
 * The status line's words. A turn still queued after a second says that it waits. It says why only
 * when the server says that every Hephaestus slot is taken. Otherwise the turn starts at once, or the
 * server cannot tell why it waits, for example when no worker runs. Most turns start well within
 * that second, so the line does not flicker.
 */
export function useTurnStatusLabel(turn: ShownTurn | undefined): string {
	const waiting = useSpinDelay(turn?.status === "QUEUED", {
		delay: 1000,
		minDuration: 500,
		// The default shows a state that is already on at mount at once, for server rendering.
		ssr: false,
	});
	return turn === undefined ? "" : turnStatusLabel(turn, waiting);
}

export interface ConversationTurnStatusProps {
	turn: Pick<ConversationTurn, "createdAt" | "status" | "surface" | "partial" | "waitingFor">;
	/** Shown on a Slack turn too. `label` names the answer it ends, apart from another scope's. */
	stop?: { label: string; onStop: () => void };
}

/**
 * Hephaestus at work: the answer so far, as Hephaestus writes it, over one status line. The line names what Hephaestus
 * does and sweeps once per change, so the motion stops while the words stay (WCAG 2.2.2).
 */
export function ConversationTurnStatus({ turn, stop }: ConversationTurnStatusProps) {
	const label = useTurnStatusLabel(turn);
	const waited = useWaitedSeconds(turn);
	const text = turn.partial?.text ?? "";
	return (
		<div className="flex flex-col gap-2">
			{hasText(text) && (
				<ConversationMessage
					message={{
						kind: "ANSWER",
						surface: turn.surface,
						text,
						createdAt: turn.createdAt,
						deleted: false,
						links: [],
					}}
					streaming
				/>
			)}
			<Marker>
				<MarkerIcon>
					<HephaestusMark
						size={16}
						pad={1}
						state={turn.status === "QUEUED" ? "quiet" : "working"}
					/>
				</MarkerIcon>
				<MarkerContent aria-hidden>
					<span key={label} className="shimmer shimmer-once">
						{label}
					</span>
					{waited !== undefined && (
						<span className="text-muted-foreground">
							{" "}
							{formatWaited(waited)} · {LEAVE_NOTE}
						</span>
					)}
				</MarkerContent>
				{stop && (
					<Button variant="outline" size="xs" aria-label={stop.label} onClick={stop.onStop}>
						{conversationMessage("stop")}
					</Button>
				)}
			</Marker>
		</div>
	);
}
