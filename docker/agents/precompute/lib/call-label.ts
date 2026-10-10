import { AsyncLocalStorage } from "node:async_hooks";

/**
 * The label of the `step` a model call runs in. It follows the async call chain, so branches that
 * run at the same time keep their own labels, whichever model object they call.
 */
export const callLabel = new AsyncLocalStorage<string>();
