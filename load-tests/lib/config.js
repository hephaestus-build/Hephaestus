import exec from "k6/execution";

/**
 * Stops the whole test run. `exec.test.abort` interrupts the calling VU, so nothing after it runs,
 * but its declared return type is `void`; the throw is what tells the type checker the same.
 * @param {string} reason - why the run cannot continue
 * @returns {never} the run is over
 */
function abort(reason) {
	exec.test.abort(reason);
	throw new Error(reason);
}

/**
 * @param {string} name
 * @returns {string}
 */
export function required(name) {
	const value = __ENV[name];
	if (value === undefined || value === "") {
		abort(`${name} is required`);
	}
	return value;
}

/**
 * @param {string} name
 * @param {number} fallback
 * @returns {number}
 */
export function integer(name, fallback) {
	const raw = __ENV[name] ?? String(fallback);
	if (!/^\d+$/u.test(raw) || !Number.isSafeInteger(Number(raw)) || Number(raw) < 1) {
		abort(`${name} must be a positive integer`);
	}
	return Number(raw);
}

export function baseUrl() {
	return required("BASE_URL").replace(/\/$/u, "");
}

/**
 * The deployed stacks serve the API under `/api`, which their proxy strips. A server reached directly,
 * such as a local one, serves it at the root: `API_PREFIX=` (empty) addresses that.
 * @returns {string}
 */
export function apiBaseUrl() {
	const prefix = __ENV.API_PREFIX ?? "/api";
	if (prefix !== "" && !/^\/[\w-]+$/u.test(prefix)) {
		abort("API_PREFIX must be empty or one path segment such as /api");
	}
	return `${baseUrl()}${prefix}`;
}

export function authHeaders() {
	return {
		Authorization: `Bearer ${required("AUTH_TOKEN")}`,
		"Content-Type": "application/json",
	};
}

/**
 * @param {Pick<import("k6/http").Response, "json">} response
 * @param {string} field
 * @returns {import("k6").JSONValue | null}
 */
export function jsonField(response, field) {
	try {
		return response.json(field);
	} catch {
		return null;
	}
}

export const sharedThresholds = {
	http_req_failed: ["rate<0.01"],
};
