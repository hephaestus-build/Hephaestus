import { type KeyboardEvent, useState } from "react";

import {
	InputGroup,
	InputGroupAddon,
	InputGroupButton,
	InputGroupTextarea,
} from "@/components/ui/input-group";
import { hasText } from "@/lib/text";

interface MessageEditorProps {
	initialContent: string;
	onCancel: () => void;
	onSend: (content: string) => void;
}

/** Replaces one of the reader's own messages while they rewrite it; sending asks Heph again from there. */
export function MessageEditor({ initialContent, onCancel, onSend }: MessageEditorProps) {
	const [draft, setDraft] = useState(initialContent);
	const canSend = hasText(draft.trim()) && draft !== initialContent;

	const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
		if (event.key === "Enter" && (event.metaKey || event.ctrlKey) && canSend) {
			event.preventDefault();
			onSend(draft);
		}
		if (event.key === "Escape") {
			event.preventDefault();
			onCancel();
		}
	};

	return (
		<InputGroup>
			<InputGroupTextarea
				aria-label="Edit message"
				className="max-h-64"
				value={draft}
				onChange={(event) => setDraft(event.target.value)}
				onKeyDown={handleKeyDown}
				// oxlint-disable-next-line jsx-a11y/no-autofocus -- This box only replaces a message once the reader presses Edit on it, so the caret belongs in the text they asked to change.
				autoFocus
			/>
			<InputGroupAddon align="block-end" className="justify-end">
				<InputGroupButton variant="outline" size="sm" onClick={onCancel}>
					Cancel
				</InputGroupButton>
				<InputGroupButton
					variant="default"
					size="sm"
					disabled={!canSend}
					onClick={() => onSend(draft)}
				>
					Send
				</InputGroupButton>
			</InputGroupAddon>
		</InputGroup>
	);
}
