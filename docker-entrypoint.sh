#!/bin/sh
set -eu

PLUGINS_DIR="${OCN_PLUGINS_LOADER_PATH:-/app/plugins}"
mkdir -p "${PLUGINS_DIR}"

fetch_plugins() {
  if [ -z "${OCN_PLUGINS:-}" ]; then
    echo "OCN_PLUGINS is empty; skipping OBS plugin fetch"
    return 0
  fi

  : "${OTC_ACCESS_KEY_ID:?OTC_ACCESS_KEY_ID is required when OCN_PLUGINS is set}"
  : "${OTC_SECRET_ACCESS_KEY:?OTC_SECRET_ACCESS_KEY is required when OCN_PLUGINS is set}"
  : "${OTC_BUCKET_NAME:?OTC_BUCKET_NAME is required when OCN_PLUGINS is set}"

  endpoint="${OTC_ENDPOINT_URL:-https://obs.eu-de.otc.t-systems.com}"
  region="${OTC_DEFAULT_REGION:-eu-de}"

  export AWS_ACCESS_KEY_ID="${OTC_ACCESS_KEY_ID}"
  export AWS_SECRET_ACCESS_KEY="${OTC_SECRET_ACCESS_KEY}"
  export AWS_DEFAULT_REGION="${region}"
  export AWS_EC2_METADATA_DISABLED=true

  # OCN_PLUGINS: comma-separated plugin ids (e.g. edx_v1,other_plugin_v3).
  # Each id maps to object key <id>.jar unless it already ends with .jar.
  remaining="${OCN_PLUGINS}"
  while [ -n "${remaining}" ]; do
    case "${remaining}" in
      *,*)
        plugin_id="${remaining%%,*}"
        remaining="${remaining#*,}"
        ;;
      *)
        plugin_id="${remaining}"
        remaining=""
        ;;
    esac

    plugin_id=$(printf '%s' "${plugin_id}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    if [ -z "${plugin_id}" ]; then
      continue
    fi

    case "${plugin_id}" in
      *.jar) object_key="${plugin_id}" ;;
      *) object_key="${plugin_id}.jar" ;;
    esac

    dest="${PLUGINS_DIR}/$(basename "${object_key}")"
    echo "Fetching s3://${OTC_BUCKET_NAME}/${object_key} -> ${dest}"
    aws s3 cp "s3://${OTC_BUCKET_NAME}/${object_key}" "${dest}" --endpoint-url "${endpoint}"
  done

  echo "Plugins ready in ${PLUGINS_DIR}:"
  ls -la "${PLUGINS_DIR}" || true
}

fetch_plugins

exec java -Dloader.path="${PLUGINS_DIR}" -jar app.jar
