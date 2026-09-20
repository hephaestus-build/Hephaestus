import { type ChangeEvent, type KeyboardEvent, useEffect, useRef, useState } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";

interface MessageEditorProps {
	initialContent: string;
	isSubmitting?: boolean;
	placeholder?: string;
	onCancel: () => void;
	onSend: (content: string) => void;
	className?: string;
}

export function MessageEditor({
	initialContent,
	isSubmitting = false,
	placeholder = "",
	onCancel,
	onSend,
	className,
}: MessageEditorProps) {
	const [draftContent, setDraftContent] = useState(initialContent);
	const textareaRef = useRef<HTMLTextAreaElement>(null);

	const adjustHeight = () => {
		if (textareaRef.current) {
			textareaRef.current.style.height = "auto";
			textareaRef.current.style.height = `${textareaRef.current.scrollHeight + 2}px`;
		}
	};

	useEffect(() => {
		const textarea = textareaRef.current;
		if (!textarea) {
			return;
		}
		textarea.style.height = "auto";
		textarea.style.height = `${textarea.scrollHeight + 2}px`;
	}, []);

	const handleInput = (event: ChangeEvent<HTMLTextAreaElement>) => {
		setDraftContent(event.target.value);
		adjustHeight();
	};

	const handleSend = () => {
		onSend(draftContent);
	};

	const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
		if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
			event.preventDefault();
			handleSend();
		}
		if (event.key === "Escape") {
			event.preventDefault();
			onCancel();
		}
	};

	const hasChanges = draftContent !== initialContent;
	const canSend = draftContent.trim().length > 0 && hasChanges && !isSubmitting;

	return (
		<div
			className={cn(
				"flex field-sizing-content min-h-16 w-full rounded-xl border border-input bg-background px-3 py-2 text-base shadow-xs transition-[color,box-shadow] outline-none placeholder:text-muted-foreground focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30 dark:aria-invalid:ring-destructive/40",
				"flex-col gap-1",
				className,
			)}
		>
			<div className="flex-1">
				<Textarea
					ref={textareaRef}
					aria-label="Edit message"
					variant="bare"
					className="min-h-0 w-full resize-none overflow-hidden"
					placeholder={placeholder}
					value={draftContent}
					onChange={handleInput}
					onKeyDown={handleKeyDown}
					disabled={isSubmitting}
					// oxlint-disable-next-line jsx-a11y/no-autofocus -- This box only replaces a message once the reader presses Edit on it, so the caret belongs in the text they asked to change.
					autoFocus
				/>
			</div>

			<div className="flex justify-end gap-2">
				<Button
					variant="outline"
					shape="pill"
					className="h-8"
					onClick={onCancel}
					disabled={isSubmitting}
					size="sm"
				>
					Cancel
				</Button>
				<Button
					variant="default"
					shape="pill"
					className="h-8"
					disabled={!canSend}
					onClick={handleSend}
					size="sm"
				>
					{isSubmitting ? "Sending..." : "Send"}
				</Button>
			</div>
		</div>
	);
}
