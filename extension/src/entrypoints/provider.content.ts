import { browser } from "@wxt-dev/browser";
import { defineContentScript } from "wxt/utils/define-content-script";

import { InlineController } from "~/content/inline-controller";
import { providerAccessSchema } from "~/content/site-access-message";

/**
 * Registered by the worker at runtime, only for provider origins the user granted
 * (`background/site-access.ts`); the manifest lists no content script and no host.
 */
export default defineContentScript({
	registration: "runtime",
	matches: [],
	cssInjectionMode: "manual",
	main(ctx) {
		const controller = new InlineController(ctx);
		const onAccess = (message: unknown, sender: { id?: string }) => {
			if (sender.id !== browser.runtime.id) {
				return;
			}
			const parsed = providerAccessSchema.safeParse(message);
			if (parsed.success && !parsed.data.enabled) {
				ctx.abort("Provider site access removed");
			}
		};
		browser.runtime.onMessage.addListener(onAccess);
		ctx.onInvalidated(() => browser.runtime.onMessage.removeListener(onAccess));
		// The provider renders and replaces its content without navigating, so every structural change —
		// and every changed link address — asks again whether the report's slot exists and still holds it — once per frame, however many
		// changes arrived. `sync` returns at once when nothing moved, including after our own insertion.
		let scheduled = false;
		const schedule = () => {
			if (scheduled) {
				return;
			}
			scheduled = true;
			ctx.requestAnimationFrame(() => {
				scheduled = false;
				controller.sync();
			});
		};
		// A provider switching between the conversation and the diff can change the URL before it shows
		// the other tab pane, so the location change is judged at once and again on the next frame.
		ctx.addEventListener(window, "wxt:locationchange", (event) => {
			controller.sync(event.newUrl.href);
			schedule();
		});
		controller.sync();
		const observer = new MutationObserver((records) => {
			controller.pageChanged(records);
			schedule();
		});
		// A list may reuse a row for other work by changing only its title link's address.
		observer.observe(document.body, {
			childList: true,
			subtree: true,
			attributes: true,
			attributeFilter: ["href"],
		});
		ctx.onInvalidated(() => {
			observer.disconnect();
		});
	},
});
