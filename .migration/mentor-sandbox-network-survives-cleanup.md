#### 🔴 Sandbox cleanup keeps mentor sandbox networks it cannot attribute

Automatic cleanup now removes a mentor conversation's sandbox network, storage and containers only
after the application container that started them has stopped or restarted. It keeps anything whose
owner it cannot establish, so two kinds of leftover now need removing by hand:

- Sandbox networks created before this upgrade record no owner and are never removed automatically.
- An application that cannot identify its own container, because it runs outside Docker or has a
  customised `hostname:`, records no owner either. If a crash leaves one of its conversation networks
  behind, that conversation cannot start a new sandbox until you remove it; the error names the
  network. Keep the default container hostname to avoid this.

A network with no recorded owner can be removed safely only while every application process of this
installation that uses the Docker daemon is stopped. Plan that pause, then follow
[Removing leftover sandbox networks](https://docs.hephaestus.build/admin/configuration-readiness#removing-leftover-sandbox-networks).
Practice review sandboxes need no action.
