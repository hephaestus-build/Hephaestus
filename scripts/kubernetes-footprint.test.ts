import assert from "node:assert/strict";
import { test } from "node:test";
import { parseContainerIdentities } from "./lib/kubernetes-footprint.ts";

await test("captures a Pending Pod initializer and later runtime without credentials", () => {
	const initializer = {
		name: "initialize",
		image: "image@sha256:init",
		resources: { limits: { memory: "1280Mi" } },
		volumeMounts: [
			{ name: "scratch", mountPath: "/tmp" },
			{ name: "credentials", mountPath: "/var/run/secrets/kubernetes.io/serviceaccount" },
		],
		env: [{ name: "PRIVATE", value: "excluded-test-marker" }],
	};
	const runtime = { name: "runtime", image: "image@sha256:runtime" };
	const volumes = [
		{ name: "scratch", emptyDir: {} },
		{ name: "credentials", projected: { sources: [] } },
	];
	const pending = parseContainerIdentities({
		items: [
			{
				metadata: { name: "review", namespace: "sandboxes" },
				spec: { initContainers: [initializer], containers: [runtime], volumes },
				status: {
					phase: "Pending",
					initContainerStatuses: [{ name: "initialize", containerID: "containerd://init-id" }],
					containerStatuses: [{ name: "runtime", state: { waiting: {} } }],
				},
			},
		],
	});
	assert.deepEqual(pending, {
		"init-id": {
			namespace: "sandboxes",
			pod: "review",
			container: "initialize",
			kind: "initContainers",
			image: initializer.image,
			resources: initializer.resources,
			mounts: initializer.volumeMounts,
			volumes: [volumes[0]],
		},
	});
	const running = parseContainerIdentities({
		items: [
			{
				metadata: { name: "review", namespace: "sandboxes" },
				spec: { initContainers: [initializer], containers: [runtime], volumes },
				status: {
					phase: "Running",
					containerStatuses: [{ name: "runtime", containerID: "containerd://runtime-id" }],
				},
			},
		],
	});
	Object.assign(pending, running);
	assert.deepEqual(Object.keys(pending).toSorted(), ["init-id", "runtime-id"]);
	assert.equal(pending["runtime-id"]?.kind, "containers");
	assert.doesNotMatch(JSON.stringify(pending), /excluded-test-marker|projected/u);
	assert.deepEqual(pending["init-id"].mounts, initializer.volumeMounts);
});

await test("keeps completed initialization metadata and rejects malformed pod collections", () => {
	const report = parseContainerIdentities({
		items: [
			{
				metadata: { name: "done", namespace: "sandboxes" },
				spec: {},
				status: {
					phase: "Succeeded",
					initContainerStatuses: [{ name: "initialize", containerID: "containerd://done" }],
				},
			},
		],
	});
	assert.equal(report.done?.kind, "initContainers");
	assert.throws(() => parseContainerIdentities({ items: {} }), /array/u);
});
