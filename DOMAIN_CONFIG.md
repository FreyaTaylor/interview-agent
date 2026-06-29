# 域名与线上部署配置

本文档记录 `interview-agent.online` 的域名、DNS、前后端线上配置现状，便于运维与排错。

> 最后更新：2026-06-30

## 1. 总览

| 项目 | 取值 |
|---|---|
| 域名 | `interview-agent.online` |
| 域名注册商（购买） | Spaceship（https://www.spaceship.com/） |
| DNS 解析托管 | Cloudflare |
| 云平台 | Azure（区域 East Asia / eastasia） |
| Azure 资源组 | `interview-agent` |
| GitHub 仓库 | `FreyaTaylor/interview-agent` |

### 对外入口

| 用途 | 域名 | 指向 |
|---|---|---|
| 前端主入口 | `https://www.interview-agent.online` | Azure Static Web Apps |
| 前端（根域名） | `https://interview-agent.online` | Azure Static Web Apps |
| 后端 API | `https://api.interview-agent.online` | Azure Container Apps |

## 2. 域名与 DNS

- **注册商**：Spaceship。域名在 Spaceship 购买，但 **Nameserver 已指向 Cloudflare**，因此所有解析记录在 Cloudflare 管理。
- **解析托管**：Cloudflare。增删改记录都在 Cloudflare DNS 面板操作。

### Cloudflare DNS 记录

> 关键约定：凡是指向 Azure 自定义域名的记录，**Proxy status 必须是 "DNS only"（灰云）**，不能是 "Proxied"（橙云），否则 Azure 域名验证与 TLS 证书签发会失败。

| 名称 | 类型 | 内容 | 代理状态 | 用途 |
|---|---|---|---|---|
| `interview-agent.online`（@） | CNAME | `agreeable-water-0b27a1900.7.azurestaticapps.net` | DNS only | 根域名 → 前端（Cloudflare CNAME flattening） |
| `www` | CNAME | `agreeable-water-0b27a1900.7.azurestaticapps.net` | DNS only | www → 前端 |
| `api` | CNAME | `iagent-backend.yellowpond-617944ef.eastasia.azurecontainerapps.io` | DNS only | api → 后端 |
| `app` | CNAME | `agreeable-water-0b27a1900.7.azurestaticapps.net` | DNS only | 备用前端子域名（未在 Azure 注册） |
| `interview-agent.online`（@） | TXT | `_roomc6wbfdi9u0gda5galqveozjvbf6` | DNS only | 前端根域名所有权验证（Static Web Apps） |
| `asuid.api` | TXT | `490CD673AEBFAA472F205DCDE52677CFBC9E6F9E8AABA82CF66F9DF9FDC28A3D` | DNS only | 后端 `api` 自定义域名验证（Container Apps） |

说明：
- 根域名（apex）不能用普通 CNAME，但 Cloudflare 支持 **CNAME flattening**，可在 @ 直接配 CNAME。
- 子域名（`api` / `www` / `app`）用普通 CNAME 即可。
- 两条 TXT 是 Azure 的域名所有权验证记录，绑定完成后保留，不要删除。

## 3. 前端配置（Azure Static Web Apps）

| 项目 | 取值 |
|---|---|
| 资源名 | `iagent-frontend` |
| 资源组 | `interview-agent` |
| 默认域名 | `agreeable-water-0b27a1900.7.azurestaticapps.net` |
| 自定义域名 | `interview-agent.online`（Ready）、`www.interview-agent.online`（Ready） |
| 技术栈 | React 19 + Vite，源码目录 `frontend-react/` |

### 构建期变量

前端通过构建时环境变量 `VITE_API_BASE` 决定后端 API 地址（见 `frontend-react/src/config.js`）：

```
VITE_API_BASE = https://api.interview-agent.online/api
```

该值存放在 GitHub Secret（仓库 `FreyaTaylor/interview-agent`），部署 workflow 构建前端时注入。修改后需重新部署前端才能生效。

## 4. 后端配置（Azure Container Apps）

| 项目 | 取值 |
|---|---|
| Container App 名 | `iagent-backend` |
| 资源组 | `interview-agent` |
| 环境（Environment） | `iagent-env` |
| 默认域名 | `iagent-backend.yellowpond-617944ef.eastasia.azurecontainerapps.io` |
| 自定义域名 | `api.interview-agent.online`（绑定状态 SniEnabled，使用 Azure managed 证书） |
| Ingress | external，目标端口 `8080` |
| 镜像仓库（ACR） | `iagentacradx8am.azurecr.io`，镜像 `interview-agent-backend` |
| 技术栈 | Java 21 + Spring Boot，源码目录 `java-backend/` |

### 关键环境变量

| 变量 | 取值 | 说明 |
|---|---|---|
| `FRONTEND_URL` | `https://www.interview-agent.online` | OAuth 登录成功后跳转的前端地址 |
| `IAGENT_CORS_ORIGINS` | `https://agreeable-water-0b27a1900.7.azurestaticapps.net,http://localhost:*,http://127.0.0.1:*,https://interview-agent.online,https://www.interview-agent.online` | CORS 白名单（含 www 与非 www） |
| `IAGENT_DEPLOY_MODE` | `hosted` | 部署模式 |
| `IAGENT_AUTH_MODE` | `github` | 认证模式：GitHub OAuth |
| `IAGENT_INVITE_REQUIRED` | `false` | 是否需要邀请码 |
| `GITHUB_CLIENT_ID` | （已配置） | GitHub OAuth App client id |
| `GITHUB_CLIENT_SECRET` | （已配置，敏感） | GitHub OAuth App client secret |

> 数据库、LLM/Embedding 等密钥同样以 Container App 环境变量 / secret 形式配置，详见 `_private/docs/AZURE_DEPLOYMENT.md`。

### 健康检查

```bash
curl https://api.interview-agent.online/actuator/health      # {"status":"UP",...}
curl https://api.interview-agent.online/api/auth/config       # {"code":0,...}
```

## 5. GitHub OAuth App

| 项目 | 取值 |
|---|---|
| Client ID | （已配置，见 GitHub OAuth App 设置） |
| Homepage URL | `https://www.interview-agent.online` |
| Authorization callback URL | `https://api.interview-agent.online/api/auth/github/callback` |

要点：
- Callback URL 指向**后端** `api` 域名（OAuth 回调由后端 `GET /api/auth/github/callback` 处理）。
- Homepage URL 指向**前端**主入口。
- OAuth App 的 Client ID / Secret 必须与后端环境变量 `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` 一致。

## 6. CI/CD

- Workflow：`.github/workflows/azure-deploy.yml`
- 触发：push 到 `main` 分支 + 手动 `workflow_dispatch`
- 流程：编译并构建后端镜像 → 推送 ACR → 更新后端 Container App；构建并部署前端到 Static Web Apps。

## 7. 常见排错

- **自定义域名 HTTPS 握手失败 / 打不开**：检查 Cloudflare 对应记录是否为 **DNS only（灰云）**；橙云会导致 Azure 证书签发失败。
- **CORS 报 `Invalid CORS request`（403）**：确认请求 Origin 在 `IAGENT_CORS_ORIGINS` 白名单内；改环境变量后会生成新 revision，需等新 revision 切到 100% 流量才生效。
- **后端域名绑定**：`api` 子域名走 CNAME + `asuid.api` TXT 验证；Container App 侧用 `az containerapp hostname add` 再 `hostname bind`。
- **前端域名绑定**：根域名走 TXT token 验证（`dns-txt-token`）；子域名走 CNAME 验证（`cname-delegation`，需先建好 CNAME 记录再注册）。
- **本机用代理（Clash/Surge 等）**：可能把 DNS 劫持成 `198.18.x.x` fake-ip，本机 `dig` / `curl` 结果不可靠；Azure 验证走公网不受影响，必要时关代理或换网络验证。
