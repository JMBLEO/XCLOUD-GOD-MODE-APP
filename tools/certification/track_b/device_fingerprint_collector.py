import json
import re
from typing import Dict, List, Any

class ExtendedDeviceFingerprintCollector:
    def __init__(self, adb_session):
        self.session = adb_session

    def sh(self, command: str) -> str:
        return self.session.sh(command)

    def extract_elf_build_id(self, package_name: str) -> str:
        apk_path = self.sh(f"pm path {package_name} | head -n 1 | cut -d: -f2")
        if not apk_path:
            return "UNKNOWN_APK_PATH"
        
        raw = self.sh(
            f"cat /proc/$(pidof {package_name})/maps | grep 'libgodmode_native.so' | head -n 1 | awk '{{print $6}}'"
        )
        target_so = raw if raw else f"{apk_path}!/lib/arm64-v8a/libgodmode_native.so"
        
        build_id = self.sh(f"readelf -n {target_so} 2>/dev/null | grep 'Build ID' | awk '{{print $3}}'")
        return build_id if build_id else "STATIC_HASH_INJECTED_AT_BUILD"

    def extract_cpu_capabilities(self) -> Dict[str, Any]:
        abi = self.sh("getprop ro.product.cpu.abi") or "arm64-v8a"
        raw_cpuinfo = self.sh("cat /proc/cpuinfo")
        
        features: List[str] = []
        for line in raw_cpuinfo.splitlines():
            if line.startswith("Features") or line.startswith("flags"):
                tokens = line.split(":", 1)[1].strip().split()
                for t in tokens:
                    if t.lower() not in features:
                        features.append(t.lower())

        num_cpus = int(self.sh("nproc") or "8")
        clusters = []
        visited_freqs = set()
        
        for cpu in range(num_cpus):
            max_freq = self.sh(f"cat /sys/devices/system/cpu/cpu{cpu}/cpufreq/cpuinfo_max_freq 2>/dev/null")
            freq_val = int(max_freq) if max_freq.isdigit() else 0
            if freq_val and freq_val not in visited_freqs:
                visited_freqs.add(freq_val)
                c_type = "little" if freq_val < 2200000 else ("mid" if freq_val < 3000000 else "prime")
                clusters.append({"type": c_type, "max_freq_khz": freq_val})

        return {
            "abi": abi,
            "cpu_features": sorted(features),
            "cores_topology": {
                "total_cores": num_cpus,
                "detected_frequencies_khz": sorted(list(visited_freqs))
            }
        }

    def extract_vulkan_extensions_snapshot(self) -> List[str]:
        raw_vk = self.sh("cmd gpu vkjson 2>/dev/null")
        extensions: List[str] = []
        
        if raw_vk and "VK_KHR" in raw_vk:
            try:
                vk_json = json.loads(raw_vk)
                for ext in vk_json.get("devices", [{}])[0].get("extensions", []):
                    extensions.append(ext.get("extensionName", ""))
            except Exception:
                pass

        if not extensions:
            raw_sf = self.sh("dumpsys SurfaceFlinger")
            matches = re.findall(r"(VK_[A-Z0-9]+_[a-z0-9_]+)", raw_sf)
            extensions = list(set(matches))

        return sorted(list(set([e for e in extensions if e])))
