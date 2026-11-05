#!/usr/bin/env bash
# 打一个可发布的 debug APK 到 dist/，并生成 sha256。
#
#   tools/release.sh              # 读 app/build.gradle.kts 的 versionName
#   tools/release.sh 0.26.0       # 显式指定版本号（校验与 gradle 一致）
#
# 产物：
#   dist/TeaTalk-<version>-debug.apk
#   dist/TeaTalk-<version>-debug.apk.sha256
#
# sha256 里的路径带仓库目录（`*TeaTalk/dist/...`），所以在 TeaTalkApp 目录下
# `cd .. && sha256sum -c TeaTalk/dist/xxx.apk.sha256` 即可校验。
#
# 之后的手动步骤见 docs/26-发布准备.md（打 tag、建 Release、上传 apk）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_VERSION="$(sed -n 's/.*versionName *= *"\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION="${1:-$GRADLE_VERSION}"

if [ "$VERSION" != "$GRADLE_VERSION" ]; then
  echo "版本号不一致：参数 $VERSION，build.gradle.kts $GRADLE_VERSION" >&2
  exit 1
fi

GRADLE_CODE="$(awk -F' *= *' '/versionCode/ {print $2; exit}' app/build.gradle.kts)"
echo "==> 版本 $VERSION ($GRADLE_CODE)"

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "警告：工作树有未提交改动，产物与任何 tag 都不同源。" >&2
fi

echo "==> 构建 $VERSION"
if [ -f "$ROOT/../.tools/gb.bat" ]; then
  ( cd "$ROOT/.." && MSYS_NO_PATHCONV=1 cmd.exe /c ".tools\\gb.bat :app:assembleDebug" )
else
  ./gradlew :app:assembleDebug
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "找不到 $APK" >&2; exit 1; }

mkdir -p dist
OUT="dist/TeaTalk-$VERSION-debug.apk"
cp "$APK" "$OUT"

# 从 TeaTalkApp 目录计算，路径形如 TeaTalk/dist/TeaTalk-x.y.z-debug.apk
PARENT="$(cd "$ROOT/.." && pwd)"
(cd "$PARENT" && sha256sum "TeaTalk/$OUT" > "$ROOT/dist/TeaTalk-$VERSION-debug.apk.sha256")

echo "==> $OUT ($(wc -c < "$OUT") 字节)"
cat "dist/TeaTalk-$VERSION-debug.apk.sha256"
