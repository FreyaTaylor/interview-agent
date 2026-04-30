"""
知识树查看页 — 纯 Streamlit 原生组件
点击叶子节点按钮 → 跳转学习页自动出题
"""
import streamlit as st
import httpx

API = "http://127.0.0.1:8000/api/knowledge"


def load_tree():
    try:
        resp = httpx.get(f"{API}/tree", timeout=30).json()
        return resp["data"] if resp["code"] == 0 else []
    except:
        st.error("无法连接后端 API")
        return []


def mastery_bar(m):
    """内联 HTML 进度条"""
    c = "#52c41a" if m >= 80 else "#faad14" if m >= 40 else "#ff4d4f" if m > 0 else "#e0e0e0"
    pt = f"{m}%" if m > 0 else "未学"
    return (
        f'<span style="display:inline-flex;align-items:center;gap:4px;">'
        f'<span style="display:inline-block;width:50px;height:4px;background:#eee;border-radius:2px;overflow:hidden;">'
        f'<span style="display:block;height:100%;width:{m}%;background:{c};border-radius:2px;"></span></span>'
        f'<span style="font-size:11px;color:#999;">{pt}</span></span>'
    )


st.title("🌳 知识树")

tree = load_tree()
if not tree:
    st.warning("暂无知识树数据，请先运行 seed_data")
    st.stop()

# Tab 切换一级分类
tabs = st.tabs([cat["name"] for cat in tree])

for tab, cat1 in zip(tabs, tree):
    with tab:
        if not cat1.get("children"):
            st.caption("暂无内容")
            continue

        for cat2 in cat1["children"]:
            with st.expander(f"📂 **{cat2['name']}**", expanded=True):
                if not cat2.get("children"):
                    st.caption("暂无知识点")
                    continue

                for leaf in cat2["children"]:
                    stars = "⭐" * leaf["interview_weight"]
                    col1, col2, col3 = st.columns([5, 2, 1.5])
                    with col1:
                        st.markdown(f"{stars} **{leaf['name']}**")
                    with col2:
                        st.markdown(mastery_bar(leaf["mastery_level"]), unsafe_allow_html=True)
                    with col3:
                        label = "复习" if leaf["study_count"] > 0 else "学习"
                        if st.button(f"📖 {label}", key=f"go_{leaf['id']}", use_container_width=True):
                            # 记录要学习的知识点，跳转学习页
                            st.session_state["start_kp_id"] = leaf["id"]
                            st.session_state["start_kp_name"] = leaf["name"]
                            st.switch_page("pages/2_study.py")
