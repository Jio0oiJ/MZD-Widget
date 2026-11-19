#!/usr/bin/env bash
# 每个小版本构建完成后执行：源码+文档 commit，打 vX.Y.Z tag，同时推到 GitHub。
# tag 是不可变快照，用于按版本回退；即便之后版本继续推进，也能随时 checkout 回去。
# 用法： bash tools/version-backup.sh [附加说明]
# 回退： git checkout vX.Y.Z   （本地无该 tag 时先 git fetch --tags）
set -e

cd "$(dirname "$0")/.."

GRADLE="mzdwidget_build/app/build.gradle"
VER=$(grep -m1 "versionName" "$GRADLE" | sed "s/.*'\(.*\)'.*/\1/")
CODE=$(grep -m1 "versionCode" "$GRADLE" | awk '{print $2}')
TAG="v${VER}"
NOTE="${1:-}"

echo "== 待备份版本：${TAG} / versionCode ${CODE} =="

# 安全闸门：确认没有把构建产物、APK、本地配置误加进来
BAD=$(git status --porcelain | awk '{print $2}' | grep -E '(^|/)(build|\.gradle)/|\.apk$|local\.properties$|\.jks$|\.keystore$' || true)
if [ -n "$BAD" ]; then
    echo "!! 检测到不应入库的文件，已中止：" >&2
    echo "$BAD" >&2
    exit 1
fi

git add -A
if git diff --cached --quiet; then
    echo "源码无改动。"
else
    MSG="release: ${TAG} (versionCode ${CODE})"
    [ -n "$NOTE" ] && MSG="${MSG} - ${NOTE}"
    git commit -q -m "$MSG"
    echo "已提交：${MSG}"
fi

# 打 annotated tag：每个小版本留一个不可变快照，便于回退
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    echo "tag ${TAG} 已存在，跳过打 tag（如需移动请先手动删除：git tag -d ${TAG}）"
else
    git tag -a "$TAG" -m "release ${TAG} (versionCode ${CODE})${NOTE:+ - $NOTE}"
    echo "已打 tag：${TAG}"
fi

if git remote get-url origin >/dev/null 2>&1; then
    # 本地分支 main、远端分支 master，必须显式写 HEAD:master
    git push -q origin HEAD:master
    echo "已推送源码到 origin/master"
    git push -q origin "$TAG"
    echo "已推送 tag ${TAG} 到 origin"
else
    echo "!! 未配置 origin，仅完成本地提交与打 tag。" >&2
    exit 2
fi

echo "回退命令：git checkout ${TAG}"
