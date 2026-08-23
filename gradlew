#!/usr/bin/env sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPS_FILE="${APP_HOME}/gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "${PROPS_FILE}" ]; then
  echo "[OwnerGuard] Missing ${PROPS_FILE}" >&2
  exit 2
fi

# The 1.0.30 forced-update overlay generates Java before Gradle starts. Normalize its
# escaped dialog line deterministically when that overlay is present. This is a no-op
# for baseline source builds and keeps local, CI, and Termux builds identical.
FORCED_UPDATE_JAVA="${APP_HOME}/app/src/main/java/com/fantest/ownerguard/AppUpdateManager.java"
FORCED_UPDATE_FIX="${APP_HOME}/tools/fix_forced_update_java_escape.py"
if [ -f "${FORCED_UPDATE_FIX}" ] && [ -f "${FORCED_UPDATE_JAVA}" ] && grep -q "static void enforceLatest(Activity activity)" "${FORCED_UPDATE_JAVA}"; then
  python3 "${FORCED_UPDATE_FIX}"
fi

DIST_URL=$(sed -n 's/^distributionUrl=//p' "${PROPS_FILE}" | head -n 1 | sed 's#\\:#:#g')
if [ -z "${DIST_URL}" ]; then
  echo "[OwnerGuard] Gradle distributionUrl is missing" >&2
  exit 3
fi

ZIP_NAME=${DIST_URL##*/}
DIST_NAME=${ZIP_NAME%-bin.zip}
DIST_NAME=${DIST_NAME%-all.zip}
CACHE_ROOT="${HOME}/.gradle/ownerguard-gradle"
GRADLE_HOME="${CACHE_ROOT}/${DIST_NAME}"
ZIP_FILE="${CACHE_ROOT}/${ZIP_NAME}"

if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
  mkdir -p "${CACHE_ROOT}"
  echo "[OwnerGuard] Downloading ${DIST_NAME}..."
  curl -L --fail --retry 4 --connect-timeout 30 -o "${ZIP_FILE}.download" "${DIST_URL}"
  mv -f "${ZIP_FILE}.download" "${ZIP_FILE}"
  rm -rf "${GRADLE_HOME}"
  unzip -q -o "${ZIP_FILE}" -d "${CACHE_ROOT}"
fi

exec "${GRADLE_HOME}/bin/gradle" -p "${APP_HOME}" "$@"
