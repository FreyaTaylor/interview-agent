"""
多页面入口
"""
import streamlit as st

st.set_page_config(page_title="面试备考 Agent", page_icon="📚", layout="wide")

pg_tree = st.Page("pages/1_knowledge_tree.py", title="知识树", icon="🌳", default=True)
pg_study = st.Page("pages/2_study.py", title="学习", icon="📖")

pg = st.navigation([pg_tree, pg_study])
pg.run()
