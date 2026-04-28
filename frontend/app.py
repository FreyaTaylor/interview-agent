"""
Phase 0 Streamlit 前端 — 学习对话界面
功能：选择知识点 → 回答问题 → 查看 Rubric 评分 → 自由探索追问
"""
import streamlit as st
import httpx
import asyncio

API_BASE = "http://localhost:8000/api/study"


def api_get(path: str, params: dict = None) -> dict:
    """同步 GET 请求"""
    resp = httpx.get(f"{API_BASE}{path}", params=params, timeout=30)
    return resp.json()


def api_post(path: str, json_data: dict) -> dict:
    """同步 POST 请求"""
    resp = httpx.post(f"{API_BASE}{path}", json=json_data, timeout=60)
    return resp.json()


# ---- 页面配置 ----
st.set_page_config(page_title="面试备考 Agent", page_icon="📚", layout="wide")
st.title("📚 面试备考 Agent — Phase 0")
st.caption("以考代学：选择知识点 → 回答问题 → 查看评分 → 自由探索追问")

# ---- 初始化 session state ----
if "conversation_id" not in st.session_state:
    st.session_state.conversation_id = None
if "phase" not in st.session_state:
    st.session_state.phase = "select"  # select | answering | scored | exploring
if "messages" not in st.session_state:
    st.session_state.messages = []
if "current_kp" not in st.session_state:
    st.session_state.current_kp = None
if "explore_count" not in st.session_state:
    st.session_state.explore_count = 0

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
                    label += f" (掌握{kp['mastery_level']}%)"
                if st.button(label, key=f"kp_{kp['id']}", use_container_width=True):
                    # 开始学习此知识点
                    start_resp = api_post("/start", {"knowledge_point_id": kp["id"]})
                    if start_resp["code"] == 0:
                        data = start_resp["data"]
                        st.session_state.conversation_id = data["conversation_id"]
                        st.session_state.phase = "answering"
                        st.session_state.current_kp = data["knowledge_point_name"]
                        st.session_state.explore_count = 0
                        st.session_state.messages = [
                            {"role": "agent", "content": f"📝 **{data['knowledge_point_name']}**\n\n{data['question_content']}"}
                        ]
                        st.rerun()
                    else:
                        st.error(start_resp.get("message", "启动失败"))
        else:
            st.warning("暂无知识点，请先运行 seed_data")
    except httpx.ConnectError:
        st.error("无法连接后端 API，请确保后端已启动: uvicorn backend.main:app --reload")

    st.divider()
    if st.button("🔄 重置对话", use_container_width=True):
        st.session_state.conversation_id = None
        st.session_state.phase = "select"
        st.session_state.messages = []
        st.session_state.current_kp = None
        st.session_state.explore_count = 0
        st.rerun()

# ---- 主区域：对话 ----
if st.session_state.phase == "select":
    st.info("👈 请从左侧选择一个知识点开始学习")
else:
    # 显示当前知识点
    st.subheader(f"📖 {st.session_state.current_kp}")

    # 显示所有消息
    for msg in st.session_state.messages:
        role = msg["role"]
        with st.chat_message("assistant" if role == "agent" else "user"):
            st.markdown(msg["content"])

    # 输入区
    if st.session_state.phase == "answering":
        answer = st.chat_input("输入你的回答...")
        if answer:
            # 显示用户回答
            st.session_state.messages.append({"role": "user", "content": answer})

            # 提交评分
            with st.spinner("🤔 正在评分..."):
                resp = api_post("/answer", {
                    "conversation_id": st.session_state.conversation_id,
                    "answer": answer,
                })

            if resp["code"] == 0:
                data = resp["data"]
                # 构建评分展示
                score_text = f"### 得分: {data['total_score']}/100\n\n"
                for item in data["rubric_result"]:
                    icon = "✅" if item["hit"] else "❌"
                    score_text += f"{icon} **{item['key_point']}** ({item['score']}分) — {item['comment']}\n\n"
                score_text += f"\n---\n💡 **反馈**: {data['feedback']}"
                score_text += f"\n\n_可以继续追问（{5 - st.session_state.explore_count}/{5}轮），或点击左侧选择下一个知识点_"

                st.session_state.messages.append({"role": "agent", "content": score_text})
                st.session_state.phase = "scored"
                st.rerun()
            else:
                st.error(resp.get("message", "评分失败"))

    elif st.session_state.phase in ("scored", "exploring"):
        explore_q = st.chat_input(f"追问（{st.session_state.explore_count}/5轮）或选择左侧下一个知识点...")
        if explore_q:
            st.session_state.messages.append({"role": "user", "content": explore_q})

            with st.spinner("🤔 思考中..."):
                resp = api_post("/explore", {
                    "conversation_id": st.session_state.conversation_id,
                    "question": explore_q,
                })

            if resp["code"] == 0:
                data = resp["data"]
                st.session_state.explore_count = data["explore_count"]
                remaining = data["max_explore"] - data["explore_count"]
                answer_text = data["answer"]
                if remaining > 0:
                    answer_text += f"\n\n_还可追问 {remaining} 轮_"
                else:
                    answer_text += "\n\n⚠️ _探索已达上限，建议进入下一个知识点_"

                st.session_state.messages.append({"role": "agent", "content": answer_text})
                st.session_state.phase = "exploring"
                st.rerun()
            else:
                st.error(resp.get("message", "探索失败"))
