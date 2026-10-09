import { useEffect, useState } from "react";
import { useSpinDelay } from "spin-delay";

import type { MentorTurn } from "@/api/types.gen";
import { HephIcon } from "@/components/brand/HephIcon";
import { Button } from "@/components/ui/button";
import { Marker, MarkerContent, MarkerIcon } from "@/components/ui/marker";
import { hasText } from "@/lib/text";

import { HephMessage } from "./HephMessage";

type ShownTurn = Pick<MentorTurn, "status" | "surface" | "partial">;

/** What the status line says while a turn runs, or while it `waiting` to start. */
function turnStatusLabel(turn: ShownTurn, waiting: boolean): string {
	if (waiting) {
		return "Heph is answering other questions first…";
	}
	if (turn.surface === "SLACK") {
		return "Answering in Slack…";
	}
	// The host words the stage from the tool in flight (`partial.stage`); the app shows it as it is.
	if (turn.partial === undefined) {
		return "Thinking…";
	}
	return turn.partial.activity === "WRITING" ? "Writing…" : turn.partial.stage;
}

/** From this many seconds, the line counts the wait and says the developer can leave. */
const LEAVE_NOTE_AFTER_SECONDS = 10;

export const LEAVE_NOTE = "You can leave. The answer stays here.";

/**
 * Whole seconds since `since`, once they reach {@link LEAVE_NOTE_AFTER_SECONDS}; before that, and
 * for a turn asked in Slack, whose answer goes there, nothing. No estimate exists, so nothing
 * shows progress either.
 */
export function useWaitedSeconds(
	turn: Pick<MentorTurn, "createdAt" | "surface"> | undefined,
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
	return seconds < 60 ? `${seconds} s` : `${Math.floor(seconds / 60)} min ${seconds % 60} s`;
}

/**
 * The status line's words. A turn still queued after a second waits because Heph is busy with other
 * questions, and says so. Most turns start well within that second, so the line does not flicker.
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

export interface HephTurnStatusProps {
	turn: Pick<MentorTurn, "createdAt" | "status" | "surface" | "partial">;
	/** Shown on a Slack turn too. `label` names the answer it ends, apart from another scope's. */
	stop?: { label: string; onStop: () => void };
}

/**
 * Heph at work: the answer so far, as Heph writes it, over one status line. The line names what Heph
 * does and sweeps once per change, so the motion stops while the words stay (WCAG 2.2.2).
 */
export function HephTurnStatus({ turn, stop }: HephTurnStatusProps) {
	const label = useTurnStatusLabel(turn);
	const waited = useWaitedSeconds(turn);
	const text = turn.partial?.text ?? "";
	return (
		<div className="flex flex-col gap-2">
			{hasText(text) && (
				<HephMessage
					message={{
						kind: "HEPH",
						surface: turn.surface,
						text,
						createdAt: turn.createdAt,
						deleted: false,
						observations: [],
					}}
					streaming
				/>
			)}
			<Marker>
				<MarkerIcon>
					<HephIcon size={16} pad={1} animated={false} />
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
						Stop
					</Button>
				)}
			</Marker>
		</div>
	);
}
