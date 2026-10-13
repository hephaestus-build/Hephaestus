import { CircleAlertIcon, CircleStopIcon, ClockIcon, RotateCcwIcon } from "lucide-react";
import type { ReactNode } from "react";

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
	| { kind: "failed"; reason: string; onTryAgain?: () => void }
	| { kind: "notSent"; reason: string; onRetry?: () => void };

function Line({
	icon,
	children,
	action,
}: {
	icon: ReactNode;
	children: ReactNode;
	action?: ReactNode;
}) {
	return (
		<Marker>
			<MarkerIcon>{icon}</MarkerIcon>
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
	return (
		<Button variant="link" size="inline" aria-label={`${label} (${subject})`} onClick={onClick}>
			{children}
		</Button>
	);
}

export interface MessageStatusProps {
	state: MessageState;
	/** As `MessageActions` takes it. */
	subject: string;
}

/** One line under a message: queued behind another turn, stopped, failed, or not sent. */
export function MessageStatus({ state, subject }: MessageStatusProps) {
	switch (state.kind) {
		case "queued": {
			return (
				<Line
					icon={<ClockIcon />}
					action={
						state.onStop && (
							<ActionButton onClick={state.onStop} label="Stop queued message" subject={subject}>
								Stop
							</ActionButton>
						)
					}
				>
					{state.next ? "Queued. Heph answers this next." : "Queued."}
				</Line>
			);
		}
		case "stopped": {
			return <Line icon={<CircleStopIcon />}>Stopped</Line>;
		}
		case "failed": {
			return (
				<Line
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
