const APPLE = /Mac|iPhone|iPad|iPod/u.test(navigator.userAgent);

export const SIDEBAR_SHORTCUT_LABEL = APPLE ? "⌘B" : "Ctrl+B";
export const SEND_SHORTCUT_LABEL = APPLE ? "⌘↵" : "Ctrl+Enter";
export const SEND_SHORTCUT_ARIA = APPLE ? "Meta+Enter" : "Control+Enter";
export const FEEDBACK_SHORTCUT_LABEL = APPLE ? "⇧⌘F" : "Ctrl+Shift+F";
export const FEEDBACK_SHORTCUT_ARIA = APPLE ? "Meta+Shift+F" : "Control+Shift+F";

/**
 * The key press that opens the feedback composer. It follows the letter that the label names, so it works on a layout
 * such as Dvorak. A layout without Latin letters never reports an F, so the physical key stands in there.
 */
export function isFeedbackShortcut(event: KeyboardEvent): boolean {
	const pressed = /^[a-z]$/iu.test(event.key)
		? event.key.toLowerCase() === "f"
		: event.code === "KeyF";
	return (event.metaKey || event.ctrlKey) && event.shiftKey && pressed;
}
