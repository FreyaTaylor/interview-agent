"""
学习页 — 对话式学习
支持从知识树跳转（自动出题）或侧边栏选择
"""
import streamlit as st
import httpx

API_BASE = "http://127.0.0.1:8000/api/study"

CUSTOM_CSS = """
<style>
.question-box { background-color: #e8f4fd; border-left: 4px solid #2196F3; padding: 12px 16px; border-radius: 6px; margin: 6px 0; }
.answer-box { background-color: #fff8e1; border-left: 4px solid #FF9800; padding: 12px 16px; border-radius: 6px; margin: 6px 0; }
.score-box { background-color: #e8f5e9; border-left: 4px solid #4CAF50; padding: 12px 16px; border-radius: 6px; margin: 6px 0; }
.round-group { border: 1px solid #e0e0e0; border-radius: 10px; padding: 16px; margin: 12px 0; background-color: #fafafa; }
</style>
"""

def api_get(path, params=None):
    for i in range(3):
        try: return httpx.get(f"{API_BASE}{path}", params=params, timeout=30).json()
        except: import time; time.sleep(1) if i < 2 else (_ for _ in ()).throw(Exception())

def api_post(path, data):
    return httpx.post(f"{API_BASE}{path}", json=data, timeout=120).json()

def render_round_group(messages):
    html = '<div class="round-group">'
    for msg in messages:
        t = msg.get("type", "question")
        css = {"question": "question-box", "answer": "answer-box", "score": "score-box"}.get(t, "question-box")
        html += f'<div class="{css}">{msg["html"]}</div>'
    return html + '</div>'

def start_study(kp_id):
    """调用 /start API 开始学习"""
    resp = api_post("/start", {"knowledge_point_id": kp_id})
    if resp["code"] == 0:
        d = resp["data"]
        st.session_state.conversation_id = d["conversation_id"]
        st.session_state.session_id = d["session_id"]
        st.session_state.phase = "answering"
        st.session_state.current_kp = d["knowledge_point_name"]
        st.session_state.question_round = d["question_round"]
        st.session_state.round_groups = []
        st.session_state.current_round = [
            {"type": "question", "html": f"📝 <b>第{d['question_round']}题</b><br>{d['question_content']}"}
        ]
        return True
    else:
        st.error(resp.get("message", "启动失败"))
        return False


st.markdown(CUSTOM_CSS, unsafe_allow_html=True)
st.title("📖 学习")

# ---- 初始化 ----
for key, default in [
    ("conversation_id", None), ("session_id", None),
    ("phase", "select"), ("round_groups", []),
    ("current_round", []), ("current_kp", None),
    ("question_round", 0),
]:
    if key not in st.session_state:
        st.session_state[key] = default

# ---- 从知识树跳转：自动出题 ----
if "start_kp_id" in st.session_state and st.session_state.get("start_kp_id"):
    kp_id = st.session_state.pop("start_kp_id")
    st.session_state.pop("start_kp_name", None)
    with st.spinner("🧠 正在出题..."):
        start_study(kp_id)

# ---- 侧边栏 ----
with st.sidebar:
    st.header("📋 知识点列表")
    try:
        resp = api_get("/knowledge-points")
        if resp["code"] == 0 and resp["data"]:
            for kp in resp["data"]:
                label = f"{'⭐' * kp['interview_weight']} {kp['name']}"
                if kp["mastery_level"] > 0:
                    label += f" ({kp['mastery_level']}%)"
                if st.button(label, key=f"kp_{kp['id']}", use_container_width=True):
                    with st.spinner("🧠 正在出题..."):
                        if start_study(kp["id"]):
                            st.rerun()
        else:
            st.warning("暂无知识点")
    except:
        st.error("无法连接后端 API")

    st.divider()
    col1, col2 = st.columns(2)
    with col1:
        if st.button("🔄 重置", use_container_width=True):
            for k in ["conversation_id", "session_id", "current_kp"]:
                st.session_state[k] = None
            st.session_state.phase = "select"
            st.session_state.round_groups = []
            st.session_state.current_round = []
            st.session_state.question_round = 0
            st.rerun()
    with col2:
        if st.button("🌳 知识树", use_container_width=True):
            st.switch_page("pages/1_knowledge_tree.py")

# ---- 主区域 ----
if st.session_state.phase == "select":
    st.info("👈 从左侧选择知识点，或从知识树页面点击进入")
else:
    st.subheader(f"📖 {st.session_state.current_kp}")

    for group in st.session_state.round_groups:
        st.markdown(render_round_group(group), unsafe_allow_html=True)
    if st.session_state.current_round:
        st.markdown(render_round_group(st.session_state.current_round), unsafe_allow_html=True)

    if st.session_state.phase == "answering":
        answer = st.chat_input("输入你的回答...")
        if answer:
            st.session_state.current_round.append({"type": "answer", "html": f"💬 {answer}"})
            with st.spinner("🤔 正在评分..."):
                resp = api_post("/answer", {
                    "conversation_id": st.session_state.conversation_id, "answer": answer,
                })
            if resp["code"] == 0:
                data = resp["data"]
                score_html = f"<b>得分: {data['total_score']}/100</b> — {data['feedback']}<br>"
                score_html += '<table style="width:100%; border-collapse:collapse; margin:8px 0; font-size:14px;">'
                for item in data["rubric_result"]:
                    icon = "✅" if item["hit"] else "❌"
                    matched = item.get("matched_text", "") or ""
                    row_bg = "#e8f5e9" if item["hit"] else "#ffebee"
                    md = f'<span style="color:#666;font-style:italic;">「{matched}」</span>' if matched else '<span style="color:#999;">未提及</span>'
                    score_html += f'<tr style="background:{row_bg};border-bottom:1px solid #e0e0e0;"><td style="padding:4px 8px;">{icon} <b>{item["key_point"]}</b>（{item["score"]}分）<br>{md}</td></tr>'
                score_html += '</table>'
                rec = data.get("recommended_answer")
                if rec:
                    score_html += '<br>📖 <b>推荐回答</b>:<br>'
                    if isinstance(rec, list):
                        for j, p in enumerate(rec, 1):
                            score_html += f'{j}. {p}<br>'
                    else:
                        score_html += f'{rec}'
                if data.get("follow_up"):
                    st.session_state.current_round.append({"type": "score", "html": score_html})
                    st.session_state.round_groups.append(st.session_state.current_round)
                    st.session_state.current_round = [
                        {"type": "question", "html": f"🤔 <b>追问</b><br>{data['follow_up']}"}
                    ]
                else:
                    st.session_state.current_round.append({"type": "score", "html": score_html})
                    st.session_state.round_groups.append(st.session_state.current_round)
                    st.session_state.current_round = []
                    st.session_state.phase = "scored"
                st.rerun()
            else:
                st.error(resp.get("message", "评分失败"))

    elif st.session_state.phase == "scored":
        col1, col2 = st.columns([1, 4])
        with col1:
            if st.button("➡️ 下一题", use_container_width=True):
                with st.spinner("🧠 正在出题..."):
                    resp = api_post("/next", {"conversation_id": st.session_state.conversation_id})
                if resp["code"] == 0:
                    data = resp["data"]
                    st.session_state.question_round = data["question_round"]
                    st.session_state.phase = "answering"
                    st.session_state.current_round = [
                        {"type": "question", "html": f"📝 <b>第{data['question_round']}题</b><br>{data['question_content']}"}
                    ]
                    st.rerun()
                else:
                    st.error(resp.get("message", "出题失败"))
