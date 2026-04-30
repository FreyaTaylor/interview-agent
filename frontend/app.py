"""
面试备考 Agent 前端 — 学习对话界面
流程：选择知识点 → LLM 动态出题 → 回答 → Rubric 评分 → LLM 决定追问或下一题
"""
import streamlit as st
import httpx

API_BASE = "http://127.0.0.1:8000/api/study"

# ---- 样式 ----
CUSTOM_CSS = """
<style>
.question-box {
    background-color: #e8f4fd;
    border-left: 4px solid #2196F3;
    padding: 12px 16px;
    border-radius: 6px;
    margin: 6px 0;
}
.answer-box {
    background-color: #fff8e1;
    border-left: 4px solid #FF9800;
    padding: 12px 16px;
    border-radius: 6px;
    margin: 6px 0;
}
.score-box {
    background-color: #e8f5e9;
    border-left: 4px solid #4CAF50;
    padding: 12px 16px;
    border-radius: 6px;
    margin: 6px 0;
}
.round-group {
    border: 1px solid #e0e0e0;
    border-radius: 10px;
    padding: 16px;
    margin: 12px 0;
    background-color: #fafafa;
}
</style>
"""


def api_get(path: str, params: dict = None) -> dict:
    for attempt in range(3):
        try:
            resp = httpx.get(f"{API_BASE}{path}", params=params, timeout=30)
            return resp.json()
        except (httpx.RemoteProtocolError, httpx.ConnectError):
            if attempt == 2:
                raise
            import time
            time.sleep(1)


def api_post(path: str, json_data: dict) -> dict:
    resp = httpx.post(f"{API_BASE}{path}", json=json_data, timeout=120)
    return resp.json()


def render_round_group(messages: list[dict]) -> str:
    html = '<div class="round-group">'
    for msg in messages:
        t = msg.get("type", "question")
        css_class = {"question": "question-box", "answer": "answer-box", "score": "score-box"}.get(t, "question-box")
        html += f'<div class="{css_class}">{msg["html"]}</div>'
    html += '</div>'
    return html


st.set_page_config(page_title="面试备考 Agent", page_icon="📚", layout="wide")
st.markdown(CUSTOM_CSS, unsafe_allow_html=True)
st.title("📚 面试备考 Agent")
st.caption("以考代学：选择知识点 → 回答问题 → 查看评分 → 追问/下一题")

# ---- 初始化 session state ----
for key, default in [
    ("conversation_id", None), ("session_id", None),
    ("phase", "select"), ("round_groups", []),
    ("current_round", []), ("current_kp", None),
    ("question_round", 0),
]:
    if key not in st.session_state:
        st.session_state[key] = default

# ---- 侧边栏：选择知识点 ----
with st.sidebar:
    st.header("📋 知识点列表")
    try:
        resp = api_get("/knowledge-points")
        if resp["code"] == 0 and resp["data"]:
            kp_list = resp["data"]
            for kp in kp_list:
                label = f"{'⭐' * kp['interview_weight']} {kp['name']}"
                if kp["mastery_level"] > 0:
                    label += f" ({kp['mastery_level']}%)"
                if st.button(label, key=f"kp_{kp['id']}", use_container_width=True):
                    with st.spinner("🧠 正在出题..."):
                        start_resp = api_post("/start", {"knowledge_point_id": kp["id"]})
                    if start_resp["code"] == 0:
                        data = start_resp["data"]
                        st.session_state.conversation_id = data["conversation_id"]
                        st.session_state.session_id = data["session_id"]
                        st.session_state.phase = "answering"
                        st.session_state.current_kp = data["knowledge_point_name"]
                        st.session_state.question_round = data["question_round"]
                        st.session_state.round_groups = []
                        st.session_state.current_round = [
                            {"type": "question", "html": f"📝 <b>第{data['question_round']}题</b><br>{data['question_content']}"}
                        ]
                        st.rerun()
                    else:
                        st.error(start_resp.get("message", "启动失败"))
        else:
            st.warning("暂无知识点，请先运行 seed_data")
    except httpx.ConnectError:
        st.error("无法连接后端 API，请确保后端已启动")

    st.divider()
    if st.button("🔄 重置对话", use_container_width=True):
        for key in ["conversation_id", "session_id", "current_kp"]:
            st.session_state[key] = None
        st.session_state.phase = "select"
        st.session_state.round_groups = []
        st.session_state.current_round = []
        st.session_state.question_round = 0
        st.rerun()

# ---- 主区域：对话 ----
if st.session_state.phase == "select":
    st.info("👈 请从左侧选择一个知识点开始学习")
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
                    "conversation_id": st.session_state.conversation_id,
                    "answer": answer,
                })
            if resp["code"] == 0:
                data = resp["data"]
                score_html = f"<b>得分: {data['total_score']}/100</b> — {data['feedback']}<br>"
                score_html += '<table style="width:100%; border-collapse:collapse; margin:8px 0; font-size:14px;">'
                for item in data["rubric_result"]:
                    icon = "✅" if item["hit"] else "❌"
                    matched = item.get("matched_text", "") or ""
                    row_bg = "#e8f5e9" if item["hit"] else "#ffebee"
                    matched_display = f'<span style="color:#666;font-style:italic;">「{matched}」</span>' if matched else '<span style="color:#999;">未提及</span>'
                    score_html += f'<tr style="background:{row_bg};border-bottom:1px solid #e0e0e0;"><td style="padding:4px 8px;">{icon} <b>{item["key_point"]}</b>（{item["score"]}分）<br>{matched_display}</td></tr>'
                score_html += '</table>'
                rec = data.get("recommended_answer")
                if rec:
                    score_html += '<br>📖 <b>推荐回答</b>:<br>'
                    if isinstance(rec, list):
                        for j, point in enumerate(rec, 1):
                            score_html += f'{j}. {point}<br>'
                    else:
                        score_html += f'{rec}'
                if data.get("follow_up"):
                    st.session_state.current_round.append({"type": "score", "html": score_html})
                    st.session_state.round_groups.append(st.session_state.current_round)
                    st.session_state.current_round = [
                        {"type": "question", "html": f"🤔 <b>追问</b><br>{data['follow_up']}"}
                    ]
                    st.session_state.phase = "answering"
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
