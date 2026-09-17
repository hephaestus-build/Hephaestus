import { toast } from "sonner";

/** A refused write — a denied permission, an unfocused document — is the reader's to hear about. */
export function copyToClipboard(content: string): void {
	navigator.clipboard.writeText(content).catch(() => {
		toast.error("Couldn't copy that to the clipboard.");
	});
}
