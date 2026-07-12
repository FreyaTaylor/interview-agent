/**
 * 知识树侧边栏组件 — ExamPage 和 LearnPage 共用
 * 递归渲染知识树，支持展开/折叠，点击叶子节点选择
 */
import { useState, useEffect } from 'react'
import { API_KNOWLEDGE } from '../config'

/**
 * 模块级共享缓存 — 让 ExamPage / LearnPage 切换时左侧目录不闪。
 * 第一次拉取后内存里留一份；之后跨路由跳转直接同步返回，后台再静默刷新一遍掌握度。
 */
let _treeCache = null
let _treePromise = null
const _subscribers = new Set()

function _emit() { _subscribers.forEach(fn => fn(_treeCache)) }

async function _fetchTree() {
  if (_treePromise) return _treePromise
  _treePromise = fetch(`${API_KNOWLEDGE}/tree`)
    .then(r => r.json())
    .then(d => {
      if (d.code === 0) {
        _treeCache = d.data
        _emit()
      }
      return _treeCache
    })
    .finally(() => { _treePromise = null })
  return _treePromise
}

/** 共享知识树 hook：同步返回缓存（若有），后台拉取最新；支持手动 refresh。*/
export function useKnowledgeTree() {
  const [tree, setTree] = useState(_treeCache || [])
  useEffect(() => {
    const sub = (t) => setTree(t || [])
    _subscribers.add(sub)
    // 没缓存时主动拉一次；有缓存时也异步刷新以更新掌握度
    _fetchTree()
    return () => { _subscribers.delete(sub) }
  }, [])
  return tree
}

/** 手动失效缓存并重新拉取（如答完题想刷新掌握度）。*/
export function refreshKnowledgeTree() {
  _treeCache = null
  return _fetchTree()
}

/** 查找节点的所有祖先 ID（用于自动展开路径） */
export function findAncestorIds(tree, targetId) {
  const ancestors = new Set()
  function dfs(node, path) {
    if (node.id === targetId) {
      path.forEach(id => ancestors.add(id))
      return true
    }
    for (const child of (node.children || [])) {
      if (dfs(child, [...path, node.id])) return true
    }
    return false
  }
  for (const root of tree) {
    if (dfs(root, [])) break
  }
  return ancestors
}

/** 侧边栏树节点（递归） */
export function SidebarNode({ node, activeId, expandedIds, onSelect, depth = 0 }) {
  const children = node.children || []
  const hasKids = children.length > 0
  const isLeaf = node.node_type === 'knowledge_point'
  const isActive = node.id === activeId
  const shouldExpand = expandedIds.has(node.id)
  const [collapsed, setCollapsed] = useState(!shouldExpand)

  // 当 activeId 变化时自动展开祖先，折叠非祖先
  useEffect(() => {
    if (shouldExpand) setCollapsed(false)
    else if (expandedIds.size > 0) setCollapsed(true)
  }, [shouldExpand, expandedIds])

  return (
    <div>
      <div
        className={`learn-sidebar-item ${isActive ? 'active' : ''} ${isLeaf ? 'leaf' : ''}`}
        style={{ paddingLeft: 12 + depth * 16 }}
        onClick={() => {
          if (isLeaf) onSelect(node.id)
          else setCollapsed(c => !c)
        }}
      >
        {hasKids && <span className={`learn-sidebar-toggle ${collapsed ? '' : 'open'}`} />}
        {isLeaf && <span className="learn-sidebar-bullet" />}
        <span className="learn-sidebar-name">{node.name}</span>
      </div>
      {hasKids && !collapsed && children.map(ch => (
        <SidebarNode key={ch.id} node={ch} activeId={activeId} expandedIds={expandedIds} onSelect={onSelect} depth={depth + 1} />
      ))}
    </div>
  )
}
