import { fromMarkdown } from "mdast-util-from-markdown";
import { useEffect, useState } from "react";

import { conversationMessage } from "@/lib/conversation-copy";

interface StreamAnswer {
	turnId: string;
	text: string;
	complete: boolean;
}

type MarkdownBlock = ReturnType<typeof fromMarkdown>["children"][number];

function spokenText(node: MarkdownBlock): string {
	if (node.type === "text" || node.type === "inlineCode" || node.type === "code") {
		return node.value;
	}
	if (node.type === "image" || node.type === "imageReference") {
		return node.alt ?? "";
	}
	if (node.type === "break") {
		return " ";
	}
	if ("children" in node) {
		const separator = node.type === "list" || node.type === "blockquote" ? " " : "";
		return node.children.map(spokenText).join(separator);
	}
	return "";
}

function completeParagraphs({ text, complete }: StreamAnswer) {
	const { children } = fromMarkdown(text);
	// The last block can still change as more Markdown arrives, including lines inside a code block.
	const blocks = complete ? children : children.slice(0, -1);
	return blocks.map((block) => spokenText(block).trim()).filter(Boolean);
}

interface Announcement {
	key: string;
	text: string;
}

export function StreamAnnouncements({ answers }: { answers: StreamAnswer[] }) {
	const [counts, setCounts] = useState(() => new Map<string, number>());
	const [pending, setPending] = useState<Announcement[]>([]);
	const next = new Map<string, number>();
	const additions = answers.flatMap((answer) => {
		const parts = completeParagraphs(answer);
		// Keep the count if a reconnect starts with a shorter snapshot of the same turn.
		const count = counts.get(answer.turnId) ?? 0;
		next.set(answer.turnId, Math.max(count, parts.length));
		return parts.slice(count).map((text, index) => ({
			key: `${answer.turnId}-${count + index}`,
			text,
		}));
	});
	if (
		counts.size !== next.size ||
		additions.length > 0 ||
		[...next.keys()].some((id) => !counts.has(id))
	) {
		setCounts(next);
		if (additions.length > 0) {
			setPending(additions);
		}
	}
	useEffect(() => {
		if (pending.length === 0) {
			return;
		}
		// Remove the live text after assistive technology has received the additions.
		const timer = window.setTimeout(() => setPending([]), 1000);
		return () => window.clearTimeout(timer);
	}, [pending]);
	return (
		<div
			role="status"
			aria-label={conversationMessage("streamedAnswer")}
			aria-atomic="false"
			aria-relevant="additions"
			className="sr-only"
		>
			{pending.map(({ key, text }) => (
				<p key={key} dir="auto">
					{text}
				</p>
			))}
		</div>
	);
}
