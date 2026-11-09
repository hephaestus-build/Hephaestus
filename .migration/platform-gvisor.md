#### 🔴 Let the one-host installer reach the gVisor download

**Affected**: one-host k3s installations. Other clusters are not affected.

The installer now downloads the pinned gVisor release from `storage.googleapis.com` and checks its SHA-512. Allow that address in the host's egress rules before you run the installer of this release. The first run with gVisor restarts k3s once, which stops the running Pods for a short time.
