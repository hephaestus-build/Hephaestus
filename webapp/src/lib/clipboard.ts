import { toast } from "sonner";

/**
 * A refused write — a denied permission, an unfocused document — is the reader's to hear about;
 * `copied` is announced only once the write has landed.
 */
export function copyToClipboard(content: string, copied?: string): void {
	navigator.clipboard
		.writeText(content)
		.then(() => {
			if (copied !== undefined) {
				toast.success(copied);
			}
		})
		.catch(() => {
			toast.error("Couldn't copy that to the clipboard.");
		});
}
