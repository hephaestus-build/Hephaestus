import type { InputHTMLAttributes } from "react";
import { Streamdown } from "streamdown";

import { MarkdownCode } from "@/components/common/MarkdownCode";

function MarkdownTaskCheckbox(props: InputHTMLAttributes<HTMLInputElement>) {
	return (
		<input {...props} aria-label={props.checked === true ? "Completed task" : "Incomplete task"} />
	);
}

const MESSAGE_MARKDOWN_COMPONENTS = {
	code: MarkdownCode,
	input: MarkdownTaskCheckbox,
};

export interface MessageTextProps {
	/** One part's visible text (`visiblePartText`), as the mentor wrote it or the reader sent it. */
	text: string;
	/** Confidential surfaces must not fetch URLs embedded in generated images. */
	allowImages?: boolean;
	streaming?: boolean;
}

/**
 * One message part as the reader sees it, in every surface that shows a mentor conversation: the
 * mentor's markdown, using the shared markdown renderer.
 */
export function MessageText({ text, allowImages = true, streaming = false }: MessageTextProps) {
	return (
		<Streamdown
			components={MESSAGE_MARKDOWN_COMPONENTS}
			isAnimating={streaming}
			disallowedElements={allowImages ? undefined : ["img"]}
		>
			{text}
		</Streamdown>
	);
}
