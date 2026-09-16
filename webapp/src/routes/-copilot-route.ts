export function isMentorRoute(pathname: string) {
	return pathname === "/mentor" || /^\/w\/[^/]+\/mentor(?:\/|$)/u.test(pathname);
}

export function isCopilotExcludedRoute(pathname: string) {
	return (
		isMentorRoute(pathname) ||
		/^\/(?:admin|settings|legal)(?:\/|$)/u.test(pathname) ||
		/^\/w\/[^/]+\/admin(?:\/|$)/u.test(pathname) ||
		pathname === "/imprint" ||
		pathname === "/privacy"
	);
}
