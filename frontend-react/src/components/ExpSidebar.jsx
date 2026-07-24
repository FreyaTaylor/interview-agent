/**
 * 面经侧边栏组件 —— 「看看面经」页专用（复制自 KnowledgeSidebar，改：数据源 /api/interview-exp、
 * 叶子为 question、点击跳 /interview-exp/:questionId、展示出现频率徽章 + 自评掌握度）。
 *
 * 后端 /api/interview-exp/tree 返回**扁平**列表（id/parent_id/name/level/node_type/self_mastery/
 * frequency/content_status），这里按 parent_id 组装成两层（域→问题）树。
 */
import { useState, useEffect } from 'react'
import { API_ROOT } from '../config'

const API_EXP = `${API_ROOT}/interview-exp`

// 模块级共享缓存，切换/返回不闪
let _expCache = null
let _expPromise = null
const _expSubs = new Set()

function _emitExp() { _expSubs.forEach(fn => fn(_expCache)) }

// 扁平列表 → 两层嵌套树（域→问题），按 sort_order/id 排序
function _buildTree(flat) {
  const byId = new Map()
  for (const n of flat) byId.set(n.id, { ...n, children: [] })
  const roots = []
  for (const n of flat) {
    const node = byId.get(n.id)
    if (n.parent_id != null && byId.has(n.parent_id)) byId.get(n.parent_id).children.push(node)
    else roots.push(node)
  }
  const sort = arr => {
    arr.sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0) || a.id - b.id)
    arr.forEach(c => sort(c.children))
  }
  sort(roots)
  return roots
}

async function _fetchExpTree() {
  if (_expPromise) return _expPromise
  _expPromise = fetch(`${API_EXP}/tree`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    .then(r => r.json())
    .then(d => {
      if (d.code === 0) {
        _expCache = _buildTree(d.data || [])
        _emitExp()
      }
      return _expCache
    })
    .finally(() => { _expPromise = null })
  return _expPromise
}

/** 共享面经树 hook：同步返回缓存、后台刷新。 */
export function useExpTree() {
  const [tree, setTree] = useState(_expCache || [])
  useEffect(() => {
    const sub = (t) => setTree(t || [])
    _expSubs.add(sub)
    _fetchExpTree()
    return () => { _expSubs.delete(sub) }
  }, [])
  return tree
}

/** 手动失效并重拉（如敲木鱼后刷新看过次数徽章）。 */
export function refreshExpTree() {
  _expCache = null
  return _fetchExpTree()
}

/** 找到某问题节点的所有祖先 id（自动展开路径）。 */
export function findExpAncestorIds(tree, targetId) {
  const ancestors = new Set()
  function dfs(node, path) {
    if (node.id === targetId) { path.forEach(id => ancestors.add(id)); return true }
    for (const child of (node.children || [])) if (dfs(child, [...path, node.id])) return true
    return false
  }
  for (const root of tree) if (dfs(root, [])) break
  return ancestors
}

/** 面经侧栏树节点（递归；叶子=question）。 */
export function ExpSidebarNode({ node, activeId, expandedIds, onSelect, depth = 0 }) {
  const children = node.children || []
  const hasKids = children.length > 0
  const isLeaf = node.node_type === 'question'
  const isActive = node.id === activeId
  const shouldExpand = expandedIds.has(node.id)
  const [collapsed, setCollapsed] = useState(!shouldExpand)

  useEffect(() => {
    if (shouldExpand) setCollapsed(false)
    else if (expandedIds.size > 0) setCollapsed(true)
  }, [shouldExpand, expandedIds])

  // 「不用看」：叶子看自身 skipped；域派生 —— 该域下所有问题都 skipped 则域也置灰
  const isSkipped = isLeaf
    ? !!node.skipped
    : (hasKids && children.every(c => c.node_type === 'question' && c.skipped))

  return (
    <div>
      <div
        className={`learn-sidebar-item ${isActive ? 'active' : ''} ${isLeaf ? 'leaf' : ''} ${isSkipped ? 'exp-skipped' : ''}`}
        style={{ paddingLeft: 12 + depth * 16 }}
        onClick={() => { if (isLeaf) onSelect(node.id); else setCollapsed(c => !c) }}
      >
        {hasKids && <span className={`learn-sidebar-toggle ${collapsed ? '' : 'open'}`} />}
        {isLeaf && <span className="learn-sidebar-bullet" />}
        {isSkipped && <span className="exp-skip-mark" title="不用看">🚫</span>}
        <span className="learn-sidebar-name">{node.name}</span>
        {isLeaf && node.frequency > 0 && (
          <span className="exp-freq-badge" title={`在 ${node.frequency} 篇面经中出现`}>×{node.frequency}</span>
        )}
        {isLeaf && node.view_count > 0 && (
          <span className="exp-view-badge" title={`看过 ${node.view_count} 次`}>🐟 {node.view_count}</span>
        )}
      </div>
      {hasKids && !collapsed && children.map(ch => (
        <ExpSidebarNode key={ch.id} node={ch} activeId={activeId} expandedIds={expandedIds} onSelect={onSelect} depth={depth + 1} />
      ))}
    </div>
  )
}
