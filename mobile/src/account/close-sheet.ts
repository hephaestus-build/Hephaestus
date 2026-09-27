/** What closing the account sheet needs of the navigator below it and of the router. */
interface Below {
	canGoBack: () => boolean;
	goBack: () => void;
}

/**
 * Closes the account sheet as a whole, from however deep in it the person is. Opened by a link with
 * nothing beneath it, there is nothing to go back to, so it goes home instead.
 */
export function closeSheet(below: Below | undefined, goHome: () => void): void {
	if (below?.canGoBack() === true) {
		below.goBack();
	} else {
		goHome();
	}
}
