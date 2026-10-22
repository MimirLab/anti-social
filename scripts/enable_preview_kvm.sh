#!/usr/bin/env bash
# Load the already installed Linux KVM driver after enabling VT-x in firmware.
set -euo pipefail
if ! rg -q '^flags\s*:.*\bvmx\b' /proc/cpuinfo; then
    echo 'Intel VT-x is disabled in firmware. On this ThinkPad P52:'
    echo 'F1 at startup → Security → Virtualization → Intel Virtualization Technology → Enabled; save with F10.'
    exit 1
fi
if [[ -c /dev/kvm && -r /dev/kvm && -w /dev/kvm ]]; then
    echo 'KVM device is already accessible. Run python3 scripts/preview.py --doctor.'
    exit 0
fi
if (( EUID != 0 )); then
    exec sudo bash "$0" "$@"
fi
modprobe kvm_intel
udevadm settle
test -c /dev/kvm
ls -l /dev/kvm
echo 'KVM driver loaded. Run python3 scripts/preview.py --doctor as your normal user.'
