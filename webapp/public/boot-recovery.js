// The app's own recovery from a failed chunk (`src/runtime/preload-error.ts`) exists only once the app
// runs. When a script that the app needs to start fails to load, for example because the network
// changed or a deploy removed the old chunks, nothing of the app runs and the page stays blank.
// This classic script runs before the app, as the CSP allows `script-src 'self'` and no inline code.
// It reloads the page once, sharing the app's reload marker and window so the two never loop, and
// otherwise says that the page could not load and offers a reload.
{
	const MARKER_KEY = "hephaestus.preload-error-reload-at";
	const RELOAD_WINDOW_MS = 60_000;

	const claimReload = () => {
		try {
			const now = Date.now();
			const last = Number(sessionStorage.getItem(MARKER_KEY));
			if (now - last < RELOAD_WINDOW_MS) {
				return false;
			}
			sessionStorage.setItem(MARKER_KEY, String(now));
			return true;
		} catch {
			return false;
		}
	};

	// A script's load error does not bubble, so the listener runs in the capture phase. A module script
	// whose own file or any file it imports cannot load fires it on its script element.
	/** @param {Event} event The load error, caught on its way to its element. */
	const onError = (event) => {
		const { target } = event;
		const app = document.querySelector("#app");
		if (!(target instanceof HTMLScriptElement) || target.type !== "module" || app === null) {
			return;
		}
		if (app.hasChildNodes()) {
			return;
		}
		window.removeEventListener("error", onError, true);
		if (claimReload()) {
			window.location.reload();
			return;
		}
		// The app's stylesheet may have failed too, so the message carries the little styling it needs.
		const main = document.createElement("main");
		Object.assign(main.style, {
			maxWidth: "32rem",
			margin: "20vh auto 0",
			padding: "0 1rem",
			fontFamily: "system-ui, sans-serif",
			lineHeight: "1.5",
			color: "CanvasText",
		});
		const heading = document.createElement("h1");
		heading.textContent = "We could not load this page";
		Object.assign(heading.style, { fontSize: "1.25rem", margin: "0 0 0.5rem" });
		const text = document.createElement("p");
		text.textContent = "Check your connection, then reload the page.";
		Object.assign(text.style, { margin: "0 0 1rem" });
		const reload = document.createElement("button");
		reload.type = "button";
		reload.textContent = "Reload";
		Object.assign(reload.style, { font: "inherit", padding: "0.375rem 0.75rem" });
		reload.addEventListener("click", () => {
			window.location.reload();
		});
		main.append(heading, text, reload);
		app.replaceChildren(main);
	};
	window.addEventListener("error", onError, true);
}
