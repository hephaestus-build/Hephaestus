#### 🔴 Drain sandboxes before upgrading their installation ownership

Before upgrading, drain active practice reviews and interactive conversations, then stop the
installation's workers. Assign a stable `SANDBOX_DOCKER_OWNER` to all worker-capable roles sharing
one database. Use different values for installations sharing a Docker daemon but not a database.
The default is `default`; valid values contain 1–63 lowercase letters, digits and hyphens and start
with a letter or digit.

After verifying which installation owns them, remove its remaining legacy containers and networks.
The new cleanup does not adopt containers that only carry `hephaestus.managed=true`, legacy
`agent-net-` networks, or resources labelled with another owner. Do not remove another installation's
resources. Restart the upgraded roles with the same owner value and confirm a new practice review
can complete. Apply the same drain procedure before changing the owner later.
