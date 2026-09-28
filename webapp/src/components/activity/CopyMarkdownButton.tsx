import { CopyIcon } from "@primer/octicons-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

export interface CopyMarkdownButtonProps {
	/**
	 * Copies the whole list, every page of it, and settles once the reader has been told how it went.
	 * It must start the clipboard write before it awaits anything, which Safari requires.
	 */
	onCopy: () => Promise<void>;
}

/** A work log's section action: the list as Markdown for a standup note or a brag document. */
export function CopyMarkdownButton({ onCopy }: CopyMarkdownButtonProps) {
	const [copying, setCopying] = useState(false);
	return (
		<Button
			variant="outline"
			size="sm"
			disabled={copying}
			onClick={() => {
				setCopying(true);
				void onCopy().finally(() => {
					setCopying(false);
				});
			}}
		>
			{copying ? <Spinner /> : <CopyIcon data-icon="inline-start" />}
			{copying ? "Copying…" : "Copy as Markdown"}
		</Button>
	);
}
