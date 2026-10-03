import { ArrowUpIcon, SquareIcon } from "lucide-react";
import { type SubmitEvent, type KeyboardEvent, useId, useState } from "react";

import { Button } from "~/components/common/Button";
import { MAX_MESSAGE_CHARS } from "~/shared/mentor";

export interface MentorComposerProps {
	/**
	 * The line the next message starts with, shown as it is sent; set only for a conversation's first
	 * message, which names the work it is about.
	 */
	reference?: string;
	/** A reply is being written: the reader can stop it, and cannot send another. */
	replying: boolean;
	/** Why nothing can be sent now, if nothing can; the field is disabled and says so. */
	disabledReason?: string;
	onSend: (text: string) => void;
	onStop: () => void;
}

/**
 * Where the reader writes to Heph. Nothing is sent until they press Send or Enter; Shift+Enter starts
 * a new line. While Heph answers, Send becomes Stop.
 */
export function MentorComposer({
	reference,
	replying,
	disabledReason,
	onSend,
	onStop,
}: MentorComposerProps) {
	const [text, setText] = useState("");
	const fieldId = useId();
	const hintId = useId();
	const disabled = disabledReason !== undefined;
	const limit = MAX_MESSAGE_CHARS - (reference === undefined ? 0 : reference.length + 2);
	const sendable = !disabled && !replying && text.trim() !== "";
	const send = () => {
		if (sendable) {
			onSend(text);
			setText("");
		}
	};
	const submit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		send();
	};
	const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
		if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
			event.preventDefault();
			send();
		}
	};
	return (
		<form onSubmit={submit} className="flex flex-col gap-2 border-t border-border bg-card p-3">
			{disabledReason === undefined ? null : (
				<p id={hintId} className="text-xs text-muted-foreground">
					{disabledReason}
				</p>
			)}
			{reference === undefined || disabled ? null : (
				<p id={hintId} className="text-xs break-words text-muted-foreground">
					Your first message starts with: <span className="text-foreground">{reference}</span>
				</p>
			)}
			<label htmlFor={fieldId} className="sr-only">
				Message Heph
			</label>
			<div className="flex items-end gap-2">
				<textarea
					id={fieldId}
					value={text}
					onChange={(event) => setText(event.target.value)}
					onKeyDown={onKeyDown}
					disabled={disabled}
					maxLength={limit}
					rows={3}
					placeholder="Ask Heph…"
					aria-describedby={
						disabledReason === undefined && reference === undefined ? undefined : hintId
					}
					className="min-h-16 flex-1 resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none placeholder:text-muted-foreground focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50"
				/>
				{replying ? (
					<Button variant="outline" size="icon" onClick={onStop} aria-label="Stop the reply">
						<SquareIcon aria-hidden />
					</Button>
				) : (
					<Button type="submit" variant="mentor" size="icon" disabled={!sendable} aria-label="Send">
						<ArrowUpIcon aria-hidden />
					</Button>
				)}
			</div>
		</form>
	);
}
