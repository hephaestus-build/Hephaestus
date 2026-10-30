export function isConversationRoute(pathname: string) {
	return pathname === "/conversation" || /^\/w\/[^/]+\/conversation(?:\/|$)/u.test(pathname);
}

export function isConversationPanelExcludedRoute(pathname: string) {
	return (
		isConversationRoute(pathname) ||
		/^\/(?:admin|settings|legal)(?:\/|$)/u.test(pathname) ||
		/^\/w\/[^/]+\/admin(?:\/|$)/u.test(pathname) ||
		pathname === "/imprint" ||
		pathname === "/privacy"
	);
}
