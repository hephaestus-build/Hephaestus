---
"hephaestus": minor
---

Apply a declared UFW policy from the admin VPN with a ten-minute confirmation window.
The script refuses changes when conflicting rules would survive a UFW reset.
Install the boot recovery service so an interrupted change keeps the emergency firewall active across a reboot.
Save strict reverse-path filtering for the IPv4 default-route interface before you apply the policy.
