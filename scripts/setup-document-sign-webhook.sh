#!/usr/bin/env bash
# 生成仅本项目使用的本地测试证书和秘密；不会安装系统信任或覆盖已有材料。
set -euo pipefail
umask 077
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
material_dir="$project_dir/.local/webhook"
command -v python3 >/dev/null
command -v keytool >/dev/null
mkdir -p "$project_dir/.local"
if ! mkdir "$material_dir"; then
  echo "Refusing to overwrite existing .local/webhook; inspect existing material first." >&2
  exit 1
fi
chmod 700 "$material_dir"
cleanup_password() { rm -f "$material_dir/.key-password"; }
trap cleanup_password EXIT
python3 - "$material_dir" <<'PY'
import pathlib, secrets, sys
directory = pathlib.Path(sys.argv[1])
password = secrets.token_hex(32)
(directory / '.key-password').write_text(password + '\n')
(directory / 'application.properties').write_text(
    'webhook.document-sign.signing-secret=' + secrets.token_hex(32) + '\n'
    'server.ssl.key-store-password=' + password + '\n')
PY
keytool -genkeypair -alias document-sign-webhook -keyalg RSA -keysize 3072 \
  -validity 365 -storetype PKCS12 -keystore "$material_dir/receiver.p12" \
  -storepass:file "$material_dir/.key-password" \
  -dname 'CN=localhost,OU=Local Webhook Test,O=RHC Test' \
  -ext 'SAN=DNS:localhost,IP:127.0.0.1,IP:192.168.0.18' \
  -ext 'EKU=serverAuth' >&2
keytool -exportcert -rfc -alias document-sign-webhook \
  -keystore "$material_dir/receiver.p12" -storepass:file "$material_dir/.key-password" \
  -file "$material_dir/receiver.crt" >&2
chmod 600 "$material_dir/application.properties" "$material_dir/receiver.p12" "$material_dir/receiver.crt"
echo "Created local webhook TLS material and configuration under .local/webhook (no system trust changes)."
