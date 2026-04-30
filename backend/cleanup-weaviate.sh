#!/bin/bash
# 清理 Weaviate 中所有向量索引，删除游标文件，重启后端后全量重建
# 解决"删除/撤回的消息仍在搜索上下文中出现"的存量问题
#
# 用法: ./cleanup-weaviate.sh [--dry-run]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOAD_ENV="${ROOT_DIR}/.env"

# 加载 .env
if [ -f "$LOAD_ENV" ]; then
    TMP_ENV=$(mktemp)
    tr -d '\r' < "$LOAD_ENV" > "$TMP_ENV"
    set -a
    # shellcheck disable=SC1090
    source "$TMP_ENV"
    set +a
    rm -f "$TMP_ENV"
fi

WEAVIATE_URL="${WEAVIATE_URL:-http://localhost:8080}"
CHAT_HISTORY_DIR="${SCRIPT_DIR}/chat_history"

DRY_RUN=false
if [ "${1:-}" = "--dry-run" ]; then
    DRY_RUN=true
    echo "🧪 干运行模式 - 只查看不执行"
    echo ""
fi

# ====================== 步骤 1: 检查 Weaviate 连接 ======================
echo "🔗 检查 Weaviate 连接: $WEAVIATE_URL"
CURL_AUTH=()
if [ -n "${WEAVIATE_API_KEY:-}" ]; then
    CURL_AUTH=(-H "Authorization: Bearer $WEAVIATE_API_KEY")
fi

META_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${CURL_AUTH[@]}" "$WEAVIATE_URL/v1/meta" 2>/dev/null || echo "000")
if [ "$META_STATUS" != "200" ]; then
    echo "❌ Weaviate 不可达 ($META_STATUS)，请先启动 Weaviate"
    echo "   如果无需清理 Weaviate（仅清除游标文件），添加 --skip-weaviate 参数"
    if [ "${1:-}" != "--skip-weaviate" ]; then
        exit 1
    fi
fi
echo "   ✅ Weaviate 可用"
echo ""

# ====================== 步骤 2: 删除 Weaviate SilkContext 对象 ======================
echo "📦 步骤 1/3: 删除 Weaviate 中所有 SilkContext 对象（聊天消息 + 文件索引）..."

PAGE_SIZE=100
TOTAL_DELETED=0

while true; do
    # 分页获取所有 SilkContext 对象的 UUID
    IDS=$(curl -s "${CURL_AUTH[@]}" "$WEAVIATE_URL/v1/graphql" \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"{ Get { SilkContext(limit: $PAGE_SIZE) { _additional { id } } } }\"}" \
        | python3 -c "
import sys, json
d = json.load(sys.stdin)
ids = [x['_additional']['id'] for x in d.get('data', {}).get('Get', {}).get('SilkContext', []) if x.get('_additional')]
print(' '.join(ids))
" 2>/dev/null || echo "")

    if [ -z "$IDS" ]; then
        break
    fi

    COUNT=$(echo "$IDS" | wc -w | tr -d ' ')
    echo "   找到 $COUNT 个对象，正在删除..."

    if [ "$DRY_RUN" = false ]; then
        for ID in $IDS; do
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${CURL_AUTH[@]}" -X DELETE "$WEAVIATE_URL/v1/objects/SilkContext/$ID" 2>/dev/null || echo "000")
            if [ "$HTTP_CODE" = "204" ]; then
                ((TOTAL_DELETED++)) || true
            fi
        done
        echo "   已删除本页 $COUNT 个对象"
    else
        echo "   [干运行] 将删除: $IDS"
    fi
done

if [ "$DRY_RUN" = false ]; then
    echo "   ✅ 共删除 $TOTAL_DELETED 个 SilkContext 对象"
else
    echo "   [干运行] 共发现 $TOTAL_DELETED 个待删除对象"
fi
echo ""

# ====================== 步骤 3: 删除游标文件 ======================
echo "📁 步骤 2/3: 删除 chat_history 中的 Weaviate 游标文件..."

CURSOR_COUNT=0
if [ -d "$CHAT_HISTORY_DIR" ]; then
    while IFS= read -r -d '' CURSOR_FILE; do
        if [ "$DRY_RUN" = false ]; then
            rm -f "$CURSOR_FILE"
            echo "   已删除: $CURSOR_FILE"
        else
            echo "   [干运行] 将删除: $CURSOR_FILE"
        fi
        ((CURSOR_COUNT++)) || true
    done < <(find "$CHAT_HISTORY_DIR" -name ".weaviate_cursor" -print0 2>/dev/null)
fi

if [ "$CURSOR_COUNT" -eq 0 ]; then
    echo "   没有找到游标文件（可能尚未索引）"
else
    echo "   ✅ 已删除 $CURSOR_COUNT 个游标文件"
fi
echo ""

# ====================== 步骤 4: 清理 SilkSession ======================
echo "📦 步骤 3/3: 删除 Weaviate 中所有 SilkSession 对象（会话注册，重启后自动重建）..."

while true; do
    SESSION_IDS=$(curl -s "${CURL_AUTH[@]}" "$WEAVIATE_URL/v1/graphql" \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"{ Get { SilkSession(limit: $PAGE_SIZE) { _additional { id } } } }\"}" \
        | python3 -c "
import sys, json
d = json.load(sys.stdin)
ids = [x['_additional']['id'] for x in d.get('data', {}).get('Get', {}).get('SilkSession', []) if x.get('_additional')]
print(' '.join(ids))
" 2>/dev/null || echo "")

    if [ -z "$SESSION_IDS" ]; then
        break
    fi

    COUNT=$(echo "$SESSION_IDS" | wc -w | tr -d ' ')

    if [ "$DRY_RUN" = false ]; then
        for ID in $SESSION_IDS; do
            curl -s -o /dev/null "${CURL_AUTH[@]}" -X DELETE "$WEAVIATE_URL/v1/objects/SilkSession/$ID" 2>/dev/null || true
        done
        echo "   已删除 $COUNT 个 SilkSession 对象"
    else
        echo "   [干运行] 将删除 $COUNT 个 SilkSession 对象"
    fi
done

echo ""
echo "🎉 清理完成！"
echo ""
echo "下一步: 重启后端服务"
if [ "$DRY_RUN" = false ]; then
    echo "  - 重启后自动触发全量索引"
    echo "  - 已删除/撤回的消息将不再出现在搜索上下文中"
    echo "  - 新消息会带上 messageId，日后删除时自动同步清理 Weaviate"
fi
