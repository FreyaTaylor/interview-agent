/**
 * 管理页 — 知识树 + 项目拷打 两个 Tab
 * 都使用 OutlinerEditor 组件，传不同的 API 前缀和配置
 */
import { useState, useRef, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import OutlinerEditor from '../components/OutlinerEditor'
import InterviewQuestionsEditor from '../components/InterviewQuestionsEditor'
import { API_ADMIN } from '../config'

export default function OutlinerPage() {
  const { tab } = useParams()
  const navigate = useNavigate()
  const adminTab = (tab === 'project') ? 'project' : (tab === 'interview') ? 'interview' : (tab === 'interview-exp') ? 'interview-exp' : 'tree'

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

  // ---- 新增面经对话框 ----
  const [showExpDialog, setShowExpDialog] = useState(false)
  const [expTab, setExpTab] = useState('text')       // 'text' | 'image'
  const [expText, setExpText] = useState('')
  const [expImageFile, setExpImageFile] = useState(null)
  const [expLoading, setExpLoading] = useState(false)
  const [expResult, setExpResult] = useState(null)   // 解析结果摘要
  const expFileInputRef = useRef(null)
  const expRefreshRef = useRef(null)

  // 面经「上传图片」Tab：支持 Ctrl/Cmd+V 直接粘贴剪贴板里的截图
  useEffect(() => {
    if (!showExpDialog || expTab !== 'image') return
    function onPaste(e) {
      const item = [...(e.clipboardData?.items || [])].find(it => it.type.startsWith('image/'))
      if (!item) return
      const file = item.getAsFile()
      if (file) { e.preventDefault(); setExpImageFile(file); setExpResult(null) }
    }
    document.addEventListener('paste', onPaste)
    return () => document.removeEventListener('paste', onPaste)
  }, [showExpDialog, expTab])

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

  async function handleCreateExp() {
    setExpResult(null)
    setExpLoading(true)
    try {
      let resp
      if (expTab === 'text') {
        if (!expText.trim()) { setExpLoading(false); return }
        resp = await fetch(`${API_ADMIN}/interview-exp/from-text`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: expText.trim() }),
        }).then(r => r.json())
      } else {
        if (!expImageFile) { setExpLoading(false); return }
        const formData = new FormData()
        formData.append('file', expImageFile)
        resp = await fetch(`${API_ADMIN}/interview-exp/from-image`, {
          method: 'POST', body: formData,
        }).then(r => r.json())
      }
      if (resp?.code === 0) {
        const d = resp.data
        setExpResult(d)
        if (!d.duplicate_source) {
          setExpText('')
          setExpImageFile(null)
          expRefreshRef.current?.()
        }
      } else {
        alert(resp?.message || '解析失败')
      }
    } catch (e) {
      alert('解析失败: ' + e.message)
    } finally {
      setExpLoading(false)
    }
  }

  return (
    <div className="outliner-container">
      {/* ---- Tab 栏 ---- */}
      <div style={{ display: 'flex', gap: 0, borderBottom: '1px solid #eee', marginBottom: 16 }}>
        {[{ key: 'tree', label: '🌳 知识树' }, { key: 'project', label: '🔨 项目拷打' }, { key: 'interview', label: '📋 面试复盘' }, { key: 'interview-exp', label: '📝 面经' }].map(t => (
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

      {/* ---- 面试真题 Tab ---- */}
      {adminTab === 'interview' && <InterviewQuestionsEditor />}

      {/* ---- 面经 Tab ---- */}
      {adminTab === 'interview-exp' && (
        <>
          <OutlinerEditor
            apiPrefix="interview-exp-nodes"
            storageKey="interview_exp_outliner_collapsed"
            showFrequency={true}
            placeholders={['知识域', '问题']}
            emptyText="暂无面经，点击上方按钮新增（粘贴文本或上传截图）"
            headerSlot={({ fetchData }) => {
              expRefreshRef.current = fetchData
              return (
                <button className="outliner-add-root" onClick={() => { setExpResult(null); setShowExpDialog(true) }}>+ 新增面经</button>
              )
            }}
          />

          {/* 新增面经对话框 */}
          {showExpDialog && (
            <div className="outliner-dialog-overlay" onClick={() => !expLoading && setShowExpDialog(false)}>
              <div className="outliner-dialog" onClick={e => e.stopPropagation()}>
                <div className="outliner-dialog-header">
                  <h3>新增面经</h3>
                  <button className="outliner-dialog-close" onClick={() => !expLoading && setShowExpDialog(false)}>×</button>
                </div>
                <div className="outliner-dialog-tabs">
                  {[{ key: 'text', label: '📄 粘贴文本' }, { key: 'image', label: '📷 上传图片' }].map(t => (
                    <button key={t.key} className={`outliner-dialog-tab ${expTab === t.key ? 'active' : ''}`}
                      onClick={() => setExpTab(t.key)} disabled={expLoading}>{t.label}</button>
                  ))}
                </div>
                <div className="outliner-dialog-body">
                  {expTab === 'text' && (
                    <>
                      <p className="outliner-dialog-hint">粘贴别人分享的面经文本，LLM 自动抽成规整问题清单（按知识域分类、语义去重、统计出现频率）</p>
                      <textarea className="outliner-dialog-textarea" rows={10}
                        placeholder={"例如:\n今天面了字节后端\n1. MySQL 索引什么时候会失效？\n2. Redis 持久化方式有哪些\n3. HashMap 底层原理"}
                        value={expText} onChange={e => setExpText(e.target.value)} disabled={expLoading} />
                    </>
                  )}
                  {expTab === 'image' && (
                    <>
                      <p className="outliner-dialog-hint">上传面经截图，先 OCR 转文字再解析（识别质量差时可改用粘贴文本）</p>
                      <div className="outliner-dialog-upload"
                        onClick={() => expFileInputRef.current?.click()}
                        onDragOver={e => e.preventDefault()}
                        onDrop={e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f?.type.startsWith('image/')) { setExpImageFile(f); setExpResult(null) } }}
                      >
                        {expImageFile ? <span>✅ {expImageFile.name || '剪贴板图片'} ({(expImageFile.size / 1024).toFixed(0)} KB)</span>
                          : <span>点击选择、拖拽，或 Ctrl/Cmd+V 粘贴图片到这里</span>}
                      </div>
                      <input ref={expFileInputRef} type="file" accept="image/*" style={{ display: 'none' }}
                        onChange={e => { if (e.target.files[0]) setExpImageFile(e.target.files[0]) }} />
                    </>
                  )}
                  {expResult && (
                    <p className="outliner-dialog-hint" style={{ color: expResult.duplicate_source ? '#d46b08' : '#389e0d' }}>
                      {expResult.duplicate_source
                        ? `⚠️ ${expResult.message}`
                        : `✅ 解析完成：抽取 ${expResult.total_parsed} 题，新增 ${expResult.new_questions} 题、命中已有 ${expResult.matched_questions} 题、新增 ${expResult.new_domains} 个知识域。`}
                    </p>
                  )}
                </div>
                <div className="outliner-dialog-footer">
                  <button className="outliner-dialog-cancel" onClick={() => setShowExpDialog(false)} disabled={expLoading}>关闭</button>
                  <button className="outliner-dialog-submit" onClick={handleCreateExp} disabled={expLoading}>
                    {expLoading ? '解析中...' : '解析'}
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
