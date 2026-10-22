#!/usr/bin/env bash
#
# Build the Electra Smart openHAB binding.
#
#   ./build.sh                     build the jar with the released openHAB 5.2.0 parent
#   ./build.sh --stage-upstream    copy the bundle into an openhab-addons clone and build
#                                  it against the current SNAPSHOT parent
#
# Environment:
#   OPENHAB_ADDONS_DIR   path of the openhab-addons clone (default: ../openhab-addons)
#   MVN                  maven executable (default: mvn)
#
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly BUNDLE_ID="org.openhab.binding.electrasmart"
readonly MVN="${MVN:-mvn}"
readonly ADDONS_DIR="${OPENHAB_ADDONS_DIR:-${SCRIPT_DIR}/../openhab-addons}"

usage() {
    sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

build_standalone() {
    cd "${SCRIPT_DIR}"
    "${MVN}" -s settings.xml clean install "$@"
}

stage_upstream() {
    if [[ ! -f "${ADDONS_DIR}/pom.xml" || ! -f "${ADDONS_DIR}/bundles/pom.xml" ]]; then
        echo "error: no openhab-addons checkout at ${ADDONS_DIR}" >&2
        echo "       clone it next to this repository or set OPENHAB_ADDONS_DIR" >&2
        exit 1
    fi

    local target="${ADDONS_DIR}/bundles/${BUNDLE_ID}"
    echo "==> staging ${BUNDLE_ID} in ${target}"
    rm -rf "${target}"
    mkdir -p "${target}"

    # The standalone build helpers (.github, tools, settings.xml, build.sh) must not be staged: inside
    # openhab-addons the reactor provides the static analysis configuration.
    cp "${SCRIPT_DIR}/pom.xml" "${SCRIPT_DIR}/NOTICE" "${SCRIPT_DIR}/README.md" "${target}/"
    cp -R "${SCRIPT_DIR}/src" "${target}/"
    # Build artifact of the karaf feature verification, not part of the bundle sources.
    rm -rf "${target}/src/main/history"

    # The standalone NOTICE names this repository; the upstream bundle must use the openHAB notice.
    python3 - "${target}/NOTICE" <<'PY'
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    text = handle.read()
text = text.replace("This content is an openHAB binding maintained by ElDuderinos.",
                    "This content is produced and maintained by the openHAB project.")
text = text.replace("https://github.com/ElDuderinos/electrasmart-openhab",
                    "https://github.com/openhab/openhab-addons")
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text)
PY

    # The build instructions only apply to this standalone repository.
    python3 - "${target}/README.md" <<'PY'
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    text = handle.read()
start = text.find("## Building")
end = text.find("## Troubleshooting")
if start != -1 and end > start:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text[:start] + text[end:])
PY

    local reactor_version
    reactor_version="$(grep -A2 '<artifactId>org.openhab.addons.reactor</artifactId>' "${ADDONS_DIR}/pom.xml" \
        | sed -n 's:.*<version>\(.*\)</version>.*:\1:p' | head -1)"
    if [[ -z "${reactor_version}" ]]; then
        echo "error: could not determine the openhab-addons reactor version" >&2
        exit 1
    fi

    # The staged bundle uses the reactor parent from ../pom.xml instead of the released parent.
    python3 - "${target}/pom.xml" "${reactor_version}" <<'PY'
import re
import sys

path, version = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    text = handle.read()

def replace_parent_version(match):
    return re.sub(r"<version>[^<]*</version>", f"<version>{version}</version>", match.group(0), count=1)

text, count = re.subn(r"<parent>.*?</parent>", replace_parent_version, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f"error: no parent block in {path}")
text = text.replace("<relativePath/>", "<relativePath>../pom.xml</relativePath>")

# The standalone build profile is only needed outside the openhab-addons reactor.
text = re.sub(r"\n  <profiles>.*?</profiles>\n", "\n", text, count=1, flags=re.S)
text = re.sub(r"\n{3,}", "\n\n", text)
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text)
PY

    python3 - "${ADDONS_DIR}/bundles/pom.xml" "${BUNDLE_ID}" <<'PY'
import sys

path, bundle = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    text = handle.read()

module = f"    <module>{bundle}</module>"
if module not in text:
    marker = "    <!-- bindings -->"
    if marker not in text:
        raise SystemExit(f"error: no bindings marker in {path}")
    text = text.replace(marker, marker + "\n" + module, 1)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    print(f"==> added {bundle} to bundles/pom.xml")
PY

    echo "==> building ${BUNDLE_ID} (${reactor_version})"
    "${MVN}" -f "${ADDONS_DIR}/pom.xml" -pl ":${BUNDLE_ID}" clean install "$@"
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi

if [[ "${1:-}" == "--stage-upstream" ]]; then
    shift
    stage_upstream "$@"
else
    build_standalone "$@"
fi
