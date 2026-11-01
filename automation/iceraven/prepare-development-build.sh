#!/usr/bin/env bash
# Build-only subset of patch_android_components.sh for a standalone checkout.
# Do not apply its search, toolbar, branding, or other browser behavior patches.
set -euo pipefail

python3 -m venv venv
venv/bin/python -m pip install 'glean_parser~=20.1'

venv/bin/python - <<'PY'
from pathlib import Path

# The submodule retains paths from Mozilla's larger source tree.
for path in Path('android-components').rglob('*'):
    if path.is_file() and path.suffix in {'.gradle', '.kts'}:
        text = path.read_text()
        updated = text.replace('../../../../../gradle/libs.versions.toml',
                               '../../../gradle/libs.versions.toml')
        if updated != text:
            path.write_text(updated)

for filename, old, new in [
    ('android-components/plugins/config/src/main/java/ConfigPlugin.kt',
     'mobile/android/version.txt', 'version.txt'),
    ('android-components/components/lib/crash/build.gradle', 'mobile/android/', ''),
    ('gradle/libs.versions.toml', ', version.ref = "python-envs-plugin"', ''),
]:
    path = Path(filename)
    path.write_text(path.read_text().replace(old, new))

path = Path('android-components/components/lib/integrity-googleplay/build.gradle')
text = path.read_text()
plugin = '    alias(libs.plugins.python.envs.plugin)'
if plugin not in text:
    path.write_text(text.replace('plugins {', 'plugins {\n' + plugin, 1))
PY

venv/bin/python automation/iceraven/toolkit/crashreporter/generate_crash_reporter_sources.py
mkdir -p netwerk/dns
version=$(tr -d '\r\n' < version.txt)
tag="FIREFOX-ANDROID_${version//./_}_RELEASE"
curl --fail --location --retry 3 \
  "https://raw.githubusercontent.com/mozilla-firefox/firefox/refs/tags/$tag/netwerk/dns/effective_tld_names.dat" \
  --output netwerk/dns/effective_tld_names.dat
