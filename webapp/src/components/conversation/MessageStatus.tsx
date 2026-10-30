import { CircleAlertIcon, CircleStopIcon, ClockIcon, RotateCcwIcon } from "lucide-react";
import type { ReactNode } from "react";

import { statusToneClass } from "@/components/common/status-def";
import { Button } from "@/components/ui/button";
import { Marker, MarkerContent, MarkerIcon } from "@/components/ui/marker";

/**
 * Where one message stands, when it is not simply answered. Each state carries the action that
 * fits it, so a stopped turn cannot offer Try again and a failed one cannot offer Stop.
 */
export type MessageState =
	| {
			kind: "queued";
			/** No other message waits before this one. */
			next: boolean;
			onStop?: () => void;
	  }
	| { kind: "stopped" }
	/** Hephaestus's answer to a question asked in Slack did not fully reach Slack: Slack may show part of it or none. */
	| { kind: "notFullyInSlack" }
	| { kind: "failed"; reason: string; onTryAgain?: () => void }
	| { kind: "notSent"; reason: string; onRetry?: () => void };

/**
 * The states that leave the developer something to do: an answer that failed, or a message that was
 * not sent. Their icon takes the `destructive` tone from the status tone helper, so the line stands
 * out from the lines that only report, such as Queued and Stopped. The words keep the line's muted
 * colour: a status colour reaches the icon, never the prose.
 *
 * A message not sent because too many follow-ups wait is a refusal rather than a failure, but the
 * app has only the server's words for why, the same state as a message the server could not store.
 * Either way the message is not sent and needs the developer, so both take one tone.
 */
const NEEDS_ACTION: ReadonlySet<MessageState["kind"]> = new Set(["failed", "notSent"]);

function Line({
	kind,
	icon,
	children,
	action,
}: {
	kind: MessageState["kind"];
	icon: ReactNode;
	children: ReactNode;
	action?: ReactNode;
}) {
	return (
		<Marker>
			<MarkerIcon className={NEEDS_ACTION.has(kind) ? statusToneClass("destructive") : undefined}>
				{icon}
			</MarkerIcon>
			<MarkerContent>{children}</MarkerContent>
			{action}
		</Marker>
	);
}

function ActionButton({
	onClick,
	label,
	subject,
	children,
}: {
	onClick: () => void;
	label: string;
	subject: string;
	children: ReactNode;
}) {
	// An action after the line's words, not a link in them, so its target is 24 px tall
	// (WCAG 2.5.8), as the running turn's Stop is.
	return (
		<Button variant="outline" size="xs" aria-label={`${label} (${subject})`} onClick={onClick}>
			{children}
		</Button>
	);
}

export interface MessageStatusProps {
	state: MessageState;
	/** As `MessageActions` takes it. */
	subject: string;
}

/** One line under a message: queued behind another turn, stopped, failed, not sent, or not fully in Slack. */
export function MessageStatus({ state, subject }: MessageStatusProps) {
	switch (state.kind) {
		case "queued": {
			return (
				<Line
					kind={state.kind}
					icon={<ClockIcon />}
					action={
						state.onStop && (
							<ActionButton onClick={state.onStop} label="Stop queued message" subject={subject}>
								Stop
							</ActionButton>
						)
					}
				>
					{state.next ? "Queued. This is answered next." : "Queued."}
				</Line>
			);
		}
		case "stopped": {
			return (
				<Line kind={state.kind} icon={<CircleStopIcon />}>
					Stopped
				</Line>
			);
		}
		case "notFullyInSlack": {
			return (
				<Line kind={state.kind} icon={<CircleAlertIcon />}>
					Not fully delivered to Slack. The full answer is here.
				</Line>
			);
		}
		case "failed": {
			return (
				<Line
					kind={state.kind}
					icon={<CircleAlertIcon />}
					action={
						state.onTryAgain && (
							<ActionButton onClick={state.onTryAgain} label="Try again" subject={subject}>
								<RotateCcwIcon />
								Try again
							</ActionButton>
						)
					}
				>
					{state.reason}
				</Line>
			);
		}
		case "notSent": {
			return (
				<Line
					kind={state.kind}
					icon={<CircleAlertIcon />}
					action={
						state.onRetry && (
							<ActionButton onClick={state.onRetry} label="Retry" subject={subject}>
								<RotateCcwIcon />
								Retry
							</ActionButton>
						)
					}
				>
					Not sent. {state.reason}
				</Line>
			);
		}
	}
}
