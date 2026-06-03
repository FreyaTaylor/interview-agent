/**
 * 管理页 — 知识树 + 项目拷打 两个 Tab
 * 都使用 OutlinerEditor 组件，传不同的 API 前缀和配置
 */
import { useState, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import OutlinerEditor from '../components/OutlinerEditor'
import { API_ADMIN } from '../config'

export default function OutlinerPage() {
  const { tab } = useParams()
  const navigate = useNavigate()
  const adminTab = (tab === 'project') ? 'project' : 'tree'

  // ---- 新建知识树对话框 ----
  const [showCreateDialog, setShowCreateDialog] = useState(false)
  const [createTab, setCreateTab] = useState('generate')
  const [createLoading, setCreateLoading] = useState(false)
  const [createText, setCreateText] = useState('')
  const [genName, setGenName] = useState('')
  const [genRequirements, setGenRequirements] = useState('')
  const [imageFile, setImageFile] = useState(null)
  const [mmFile, setMmFile] = useState(null)
  const fileInputRef = useRef(null)
  const mmFileInputRef = useRef(null)

  // 知识树 editor 的刷新回调
  const treeRefreshRef = useRef(null)

  // ---- 新增项目对话框 ----
  const [showProjectDialog, setShowProjectDialog] = useState(false)
  const [projectText, setProjectText] = useState('')
  const [projectLoading, setProjectLoading] = useState(false)
  const projectRefreshRef = useRef(null)

  async function handleCreate() {
    setCreateLoading(true)
    try {
      let resp
      if (createTab === 'text') {
        if (!createText.trim()) return
        resp = await fetch(`${API_ADMIN}/trees/from-text`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: createText.trim() }),
        }).then(r => r.json())
      } else if (createTab === 'generate') {
        if (!genName.trim()) return
        resp = await fetch(`${API_ADMIN}/trees/from-generate`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ tree_name: genName.trim(), requirements: genRequirements.trim() }),
        }).then(r => r.json())
      } else if (createTab === 'image') {
        if (!imageFile) return
        const formData = new FormData()
        formData.append('file', imageFile)
        resp = await fetch(`${API_ADMIN}/trees/from-image`, {
          method: 'POST', body: formData,
        }).then(r => r.json())
      } else if (createTab === 'mm') {
        if (!mmFile) return
        const formData = new FormData()
        formData.append('file', mmFile)
        resp = await fetch(`${API_ADMIN}/trees/from-mm`, {
          method: 'POST', body: formData,
        }).then(r => r.json())
      }
      if (resp?.code === 0) {
        setShowCreateDialog(false)
        setCreateText(''); setGenName(''); setGenRequirements('')
        setImageFile(null); setMmFile(null)
        treeRefreshRef.current?.()
      } else if (resp?.code === 40901) {
        alert(resp.message || '已存在同名或语义相似的知识树')
      } else {
        alert(resp?.message || '创建失败')
      }
    } catch (e) {
      alert('创建失败: ' + e.message)
    } finally {
      setCreateLoading(false)
    }
  }

  async function handleCreateProject() {
    if (!projectText.trim()) return
    setProjectLoading(true)
    try {
      const resp = await fetch(`${API_ADMIN}/project-nodes/from-text`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: projectText.trim() }),
      }).then(r => r.json())
      if (resp?.code === 0) {
        setShowProjectDialog(false)
        setProjectText('')
        projectRefreshRef.current?.()
      } else if (resp?.code === 40901) {
        alert(resp.message || '已存在同名或语义相似的项目')
      } else {
        alert(resp?.message || '创建失败')
      }
    } catch (e) {
      alert('创建失败: ' + e.message)
    } finally {
      setProjectLoading(false)
    }
  }

  return (
    <div className="outliner-container">
      {/* ---- Tab 栏 ---- */}
      <div style={{ display: 'flex', gap: 0, borderBottom: '1px solid #eee', marginBottom: 16 }}>
        {[{ key: 'tree', label: '🌳 知识树' }, { key: 'project', label: '🔨 项目拷打' }].map(t => (
          <button key={t.key} onClick={() => navigate(`/admin/${t.key}`)}
            style={{
              padding: '10px 24px', fontSize: 14, fontWeight: adminTab === t.key ? 600 : 400,
              background: 'none', border: 'none',
              borderBottom: adminTab === t.key ? '2px solid #1677ff' : '2px solid transparent',
              color: adminTab === t.key ? '#1677ff' : '#999', cursor: 'pointer',
            }}>{t.label}</button>
        ))}
      </div>

      {/* ---- 知识树 Tab ---- */}
      {adminTab === 'tree' && (
        <>
          <OutlinerEditor
            apiPrefix="tree-nodes"
            storageKey="outliner_collapsed"
            showWeight={true}
            showOptimize={true}
            placeholders={['一级分类', '二级分类', '知识点']}
            emptyText="暂无知识树节点，点击上方按钮新增"
            headerSlot={({ handleAddRoot, fetchData }) => {
              treeRefreshRef.current = fetchData
              return (
                <button className="outliner-add-root" onClick={() => setShowCreateDialog(true)}>+ 新建知识树</button>
              )
            }}
          />

          {/* 新建知识树对话框 */}
          {showCreateDialog && (
            <div className="outliner-dialog-overlay" onClick={() => !createLoading && setShowCreateDialog(false)}>
              <div className="outliner-dialog" onClick={e => e.stopPropagation()}>
                <div className="outliner-dialog-header">
                  <h3>新建知识树</h3>
                  <button className="outliner-dialog-close" onClick={() => !createLoading && setShowCreateDialog(false)}>×</button>
                </div>
                <div className="outliner-dialog-tabs">
                  {[
                    { key: 'generate', label: '🤖 LLM生成' },
                    { key: 'text', label: '📄 文本导入' },
                    { key: 'image', label: '📷 截图解析' },
                    { key: 'mm', label: '📁 文件导入' },
                  ].map(t => (
                    <button key={t.key} className={`outliner-dialog-tab ${createTab === t.key ? 'active' : ''}`}
                      onClick={() => setCreateTab(t.key)} disabled={createLoading}>{t.label}</button>
                  ))}
                </div>
                <div className="outliner-dialog-body">
                  {createTab === 'text' && (
                    <>
                      <p className="outliner-dialog-hint">粘贴文本或 Markdown 大纲，LLM 自动解析为知识树结构</p>
                      <textarea className="outliner-dialog-textarea" rows={10}
                        placeholder={"例如:\n# Java 基础\n## 集合框架\n- ArrayList vs LinkedList\n- HashMap 原理\n## 并发编程\n- synchronized\n- volatile"}
                        value={createText} onChange={e => setCreateText(e.target.value)} disabled={createLoading} />
                    </>
                  )}
                  {createTab === 'generate' && (
                    <>
                      <p className="outliner-dialog-hint">输入树名称和需求描述，LLM 一次性生成完整知识树</p>
                      <input className="outliner-dialog-input" placeholder="知识树名称，如：Java 面试知识点"
                        value={genName} onChange={e => setGenName(e.target.value)} disabled={createLoading} />
                      <textarea className="outliner-dialog-textarea" rows={6}
                        placeholder={"需求描述，例如:\n3年Java后端开发，准备面试大厂。\n重点覆盖：Spring、MySQL、Redis、分布式系统\n可以少一点：设计模式、计算机网络"}
                        value={genRequirements} onChange={e => setGenRequirements(e.target.value)} disabled={createLoading} />
                    </>
                  )}
                  {createTab === 'image' && (
                    <>
                      <p className="outliner-dialog-hint">上传知识树截图（思维导图、大纲等），LLM 自动识别并解析</p>
                      <div className="outliner-dialog-upload"
                        onClick={() => fileInputRef.current?.click()}
                        onDragOver={e => e.preventDefault()}
                        onDrop={e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f?.type.startsWith('image/')) setImageFile(f) }}
                      >
                        {imageFile ? <span>✅ {imageFile.name} ({(imageFile.size / 1024).toFixed(0)} KB)</span>
                          : <span>点击选择或拖拽图片到这里</span>}
                      </div>
                      <input ref={fileInputRef} type="file" accept="image/*" style={{ display: 'none' }}
                        onChange={e => { if (e.target.files[0]) setImageFile(e.target.files[0]) }} />
                    </>
                  )}
                  {createTab === 'mm' && (
                    <>
                      <p className="outliner-dialog-hint">上传 .mm 文件（幕布/FreeMind/XMind 等导出），直接解析为知识树（无需 LLM）</p>
                      <div className="outliner-dialog-upload"
                        onClick={() => mmFileInputRef.current?.click()}
                        onDragOver={e => e.preventDefault()}
                        onDrop={e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f?.name.endsWith('.mm')) setMmFile(f) }}
                      >
                        {mmFile ? <span>✅ {mmFile.name} ({(mmFile.size / 1024).toFixed(0)} KB)</span>
                          : <span>点击选择或拖拽 .mm 文件到这里</span>}
                      </div>
                      <input ref={mmFileInputRef} type="file" accept=".mm" style={{ display: 'none' }}
                        onChange={e => { if (e.target.files[0]) setMmFile(e.target.files[0]) }} />
                    </>
                  )}
                </div>
                <div className="outliner-dialog-footer">
                  <button className="outliner-dialog-cancel" onClick={() => setShowCreateDialog(false)} disabled={createLoading}>取消</button>
                  <button className="outliner-dialog-submit" onClick={handleCreate} disabled={createLoading}>
                    {createLoading ? '生成中...' : '创建'}
                  </button>
                </div>
              </div>
            </div>
          )}
        </>
      )}

      {/* ---- 项目拷打 Tab ---- */}
      {adminTab === 'project' && (
        <>
          <OutlinerEditor
            apiPrefix="project-nodes"
            storageKey="project_outliner_collapsed"
            showWeight={false}
            showOptimize={false}
            placeholders={['项目名', '话题', '问题']}
            emptyText="暂无项目，点击上方按钮新增"
            headerSlot={({ handleAddRoot, fetchData }) => {
              projectRefreshRef.current = fetchData
              return (
                <button className="outliner-add-root" onClick={() => setShowProjectDialog(true)}>+ 新增项目</button>
              )
            }}
          />

          {/* 新增项目对话框 */}
          {showProjectDialog && (
            <div className="outliner-dialog-overlay" onClick={() => !projectLoading && setShowProjectDialog(false)}>
              <div className="outliner-dialog" onClick={e => e.stopPropagation()}>
                <div className="outliner-dialog-header">
                  <h3>新增项目</h3>
                  <button className="outliner-dialog-close" onClick={() => !projectLoading && setShowProjectDialog(false)}>×</button>
                </div>
                <div className="outliner-dialog-body">
                  <p className="outliner-dialog-hint">描述你的项目经历，LLM 自动拆解为「话题 → 面试问题」结构</p>
                  <textarea className="outliner-dialog-textarea" rows={10}
                    placeholder={"例如:\n我在上家公司做了一个商品推荐系统，基于用户行为数据用协同过滤+深度学习做推荐。\n技术栈是 Java + Spark + Redis + Kafka，日均处理千万级用户行为日志。\n主要难点是冷启动问题和实时推荐的延迟优化。"}
                    value={projectText} onChange={e => setProjectText(e.target.value)} disabled={projectLoading} />
                </div>
                <div className="outliner-dialog-footer">
                  <button className="outliner-dialog-cancel" onClick={() => setShowProjectDialog(false)} disabled={projectLoading}>取消</button>
                  <button className="outliner-dialog-submit" onClick={handleCreateProject} disabled={projectLoading}>
                    {projectLoading ? '解析中...' : '创建'}
                  </button>
                </div>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
