#!/usr/bin/env bash
# 每个版本构建完成后执行：把源码 + 文档提交并推到 GitHub 备份
# 用法： bash tools/version-backup.sh [附加说明]
set -e

cd "$(dirname "$0")/.."

GRADLE="mzdwidget_build/app/build.gradle"
VER=$(grep -m1 "versionName" "$GRADLE" | sed "s/.*'\(.*\)'.*/\1/")
CODE=$(grep -m1 "versionCode" "$GRADLE" | awk '{print $2}')
NOTE="${1:-}"

echo "== 待备份版本：v${VER} / versionCode ${CODE} =="

# 安全闸门：确认没有把构建产物、APK、本地配置误加进来
BAD=$(git status --porcelain | awk '{print $2}' | grep -E '(^|/)(build|\.gradle)/|\.apk$|local\.properties$|\.jks$|\.keystore$' || true)
if [ -n "$BAD" ]; then
    echo "!! 检测到不应入库的文件，已中止：" >&2
    echo "$BAD" >&2
    exit 1
fi

git add -A
if git diff --cached --quiet; then
    echo "无改动，无需备份。"
    exit 0
fi

MSG="release: v${VER} (versionCode ${CODE})"
[ -n "$NOTE" ] && MSG="${MSG} - ${NOTE}"
git commit -q -m "$MSG"
echo "已提交：${MSG}"

if git remote get-url origin >/dev/null 2>&1; then
    git push -q origin HEAD
    echo "已推送 GitHub：$(git remote get-url origin)"
else
    echo "!! 未配置 origin，仅完成本地提交。" >&2
    exit 2
fi
