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
 * @param {string} name - the environment variable the scenario cannot run without
 * @returns {string} its non-empty value
 */
export function required(name) {
	const value = __ENV[name];
	if (value === undefined || value === "") {
		abort(`${name} is required`);
	}
	return value;
}

/**
 * @param {string} name - the environment variable holding a positive integer
 * @param {number} fallback - the value when the variable is unset
 * @returns {number} the configured or fallback integer
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

export function apiBaseUrl() {
	return `${baseUrl()}/api`;
}

export function authHeaders() {
	return {
		Authorization: `Bearer ${required("AUTH_TOKEN")}`,
		"Content-Type": "application/json",
	};
}

/**
 * @param {Pick<import("k6/http").Response, "json">} response - the response whose body may be JSON
 * @param {string} field - the top-level field to read
 * @returns {import("k6").JSONValue | null} the field, or null when the body is not JSON
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
