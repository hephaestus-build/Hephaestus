const APPLE = /Mac|iPhone|iPad|iPod/u.test(navigator.userAgent);

export const SIDEBAR_SHORTCUT_LABEL = APPLE ? "⌘B" : "Ctrl+B";
export const SEND_SHORTCUT_LABEL = APPLE ? "⌘↵" : "Ctrl+Enter";
export const SEND_SHORTCUT_ARIA = APPLE ? "Meta+Enter" : "Control+Enter";
export const FEEDBACK_SHORTCUT_LABEL = APPLE ? "⇧⌘F" : "Ctrl+Shift+F";
export const FEEDBACK_SHORTCUT_ARIA = APPLE ? "Meta+Shift+F" : "Control+Shift+F";

/** The key press that opens the feedback composer. The key code keeps it the same on every layout. */
export function isFeedbackShortcut(event: KeyboardEvent): boolean {
	return (event.metaKey || event.ctrlKey) && event.shiftKey && event.code === "KeyF";
}
