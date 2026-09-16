import { ArrowDown, ArrowUp, Paperclip, Square } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { type ChangeEvent, type RefObject, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { useWindowSize } from "usehooks-ts";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import type { Attachment } from "@/lib/types";

import { PreviewAttachment } from "./PreviewAttachment";

export interface MultimodalInputProps {
	status: "ready" | "submitted" | "error";
	onStop: () => void;
	attachments: Attachment[];
	// Both are absent on a surface that disables attachments.
	onAttachmentsChange?: (attachments: Attachment[]) => void;
	onFileUpload?: (files: File[]) => Promise<(Attachment | undefined)[]>;
	onSubmit: (data: { text: string; attachments: Attachment[] }) => void;
	className?: string;
	placeholder?: string;
	initialInput?: string;
	readonly?: boolean;
	disableAttachments?: boolean;
	isAtBottom?: boolean;
	scrollToBottom?: () => void;
	isCurrentVersion?: boolean;
}

export function MultimodalInput({
	status,
	onStop,
	attachments,
	onAttachmentsChange,
	onFileUpload,
	onSubmit,
	className,
	placeholder = "Send a message...",
	initialInput = "",
	readonly = false,
	disableAttachments = false,
	isAtBottom = true,
	scrollToBottom,
	isCurrentVersion = true,
}: MultimodalInputProps) {
	const textareaRef = useRef<HTMLTextAreaElement>(null);
	const fileInputRef = useRef<HTMLInputElement>(null);
	const { width } = useWindowSize();
	const [uploadQueue, setUploadQueue] = useState<string[]>([]);

	const [input, setInput] = useState(initialInput);

	const resetHeight = () => {
		if (textareaRef.current) {
			textareaRef.current.style.height = "auto";
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
		setInput(event.target.value);
		event.currentTarget.style.height = "auto";
		event.currentTarget.style.height = `${event.currentTarget.scrollHeight + 2}px`;
	};

	const submitForm = () => {
		onSubmit({
			text: input,
			attachments,
		});

		setInput("");
		resetHeight();

		if (width && width > 768) {
			textareaRef.current?.focus();
		}
	};

	const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
		const files = [...(event.target.files ?? [])];
		if (files.length === 0 || onFileUpload === undefined) {
			return;
		}

		setUploadQueue(files.map((file) => file.name));

		try {
			const uploadedAttachments = await onFileUpload(files);
			const successfullyUploadedAttachments = uploadedAttachments.filter(
				(attachment) => attachment !== undefined,
			);

			onAttachmentsChange?.([...attachments, ...successfullyUploadedAttachments]);
		} catch {
			// The queue empties either way, so without this the files vanish with no symptom.
			toast.error("Could not attach those files. Please try again.");
		}
		setUploadQueue([]);
	};

	useEffect(() => {
		if (status === "submitted" && scrollToBottom) {
			scrollToBottom();
		}
	}, [status, scrollToBottom]);

	const canSubmit = input.trim().length > 0 && uploadQueue.length === 0 && !readonly;

	return (
		<div className="relative flex w-full flex-col gap-4">
			<AnimatePresence>
				{!isAtBottom && isCurrentVersion && (
					<motion.div
						initial={{ opacity: 0, y: 10 }}
						animate={{ opacity: 1, y: 0 }}
						exit={{ opacity: 0, y: 10 }}
						transition={{ type: "spring", stiffness: 300, damping: 20 }}
						className="absolute -top-12 left-1/2 z-[95] -translate-x-1/2 rounded-full backdrop-blur-sm"
					>
						<Button
							aria-label="Scroll to latest message"
							shape="pill"
							className="border-border/50 bg-background/80 shadow-lg hover:bg-background/90 dark:bg-background/80 dark:hover:bg-background/90"
							size="icon"
							variant="outline"
							onClick={(event) => {
								event.preventDefault();
								scrollToBottom?.();
							}}
						>
							<ArrowDown />
						</Button>
					</motion.div>
				)}
			</AnimatePresence>

			{!disableAttachments && (
				<input
					type="file"
					className="pointer-events-none fixed -top-4 -left-4 size-0.5 opacity-0"
					ref={fileInputRef}
					multiple
					aria-label="Attach files"
					onChange={(event) => {
						void handleFileChange(event);
					}}
					tabIndex={-1}
				/>
			)}

			{(attachments.length > 0 || uploadQueue.length > 0) && (
				<div className="flex flex-row items-end gap-2 overflow-x-scroll">
					{attachments.map((attachment) => (
						<PreviewAttachment key={attachment.url} attachment={attachment} />
					))}

					{uploadQueue.map((filename) => (
						<PreviewAttachment
							key={filename}
							attachment={{
								url: "",
								name: filename,
								contentType: "",
							}}
							isUploading
						/>
					))}
				</div>
			)}

			<div
				className={cn(
					"flex field-sizing-content min-h-16 w-full rounded-xl border border-input bg-transparent px-3 py-2 text-base shadow-xs transition-[color,box-shadow] outline-none placeholder:text-muted-foreground focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30 dark:aria-invalid:ring-destructive/40",
					"flex-col gap-1",
					readonly && "cursor-not-allowed opacity-60",
					className,
				)}
			>
				<div className="flex-1">
					<Textarea
						ref={textareaRef}
						aria-label="Message"
						placeholder={placeholder}
						value={input}
						onChange={handleInput}
						readOnly={readonly}
						variant="bare"
						className="min-h-0 w-full resize-none overflow-hidden"
						rows={2}
						// oxlint-disable-next-line jsx-a11y/no-autofocus -- The composer is the only writable control on every surface that mounts it, each reached in order to type. A read-only replay takes no focus.
						autoFocus={!readonly}
						onKeyDown={(event) => {
							if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
								event.preventDefault();

								if (status !== "ready") {
									return;
								}

								if (canSubmit) {
									submitForm();
								}
							}
						}}
					/>
				</div>

				<div className="flex justify-between gap-2">
					<div className="flex gap-2">
						{!disableAttachments && (
							<AttachmentsButton fileInputRef={fileInputRef} status={status} readonly={readonly} />
						)}
					</div>
					<div>
						{status === "submitted" ? (
							<StopButton onStop={onStop} />
						) : (
							<SendButton onSubmit={submitForm} disabled={!canSubmit} />
						)}
					</div>
				</div>
			</div>
		</div>
	);
}

function AttachmentsButton({
	fileInputRef,
	status,
	readonly,
}: {
	fileInputRef: RefObject<HTMLInputElement | null>;
	status: "ready" | "submitted" | "error";
	readonly: boolean;
}) {
	return (
		<Button
			aria-label="Attach a file"
			onClick={(event) => {
				event.preventDefault();
				fileInputRef.current?.click();
			}}
			disabled={status !== "ready" || readonly}
			variant="ghost"
			size="icon"
		>
			<Paperclip size={14} />
		</Button>
	);
}

function StopButton({ onStop }: { onStop: () => void }) {
	return (
		<Button
			aria-label="Stop generating"
			shape="pill"
			className="border border-border"
			onClick={(event) => {
				event.preventDefault();
				onStop();
			}}
			size="icon"
		>
			<Square fill="currentColor" strokeWidth={0} />
		</Button>
	);
}

function SendButton({ onSubmit, disabled }: { onSubmit: () => void; disabled: boolean }) {
	return (
		<Button
			aria-label="Send message"
			shape="pill"
			className="border border-border"
			onClick={(event) => {
				event.preventDefault();
				onSubmit();
			}}
			disabled={disabled}
			size="icon"
		>
			<ArrowUp size={14} strokeWidth={3} />
		</Button>
	);
}
