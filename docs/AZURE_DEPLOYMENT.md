# Azure 自动部署方案

本文档记录当前推荐的 Azure 部署方式：

- 后端：Azure Container Apps
- 镜像仓库：Azure Container Registry
- 前端：Azure Static Web Apps
- 数据库：Azure Database for PostgreSQL Flexible Server（启用 pgvector）
- CI/CD：GitHub Actions

## 1. Azure 资源

建议先创建以下资源：

| 资源 | 用途 |
|---|---|
| Resource Group | 统一管理本项目资源 |
| Azure Database for PostgreSQL Flexible Server | Java 后端数据库 |
| Azure Container Registry | 存放后端 Docker 镜像 |
| Azure Container Apps Environment | 承载后端 Container App |
| Backend Container App | 运行 `java-backend` |
| Azure Static Web Apps | 托管 React 前端 |

数据库需要支持 pgvector。初始化后确认：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

## 2. 后端 Container App 环境变量

后端 Container App 需要配置这些环境变量或 secret：

```bash
IAGENT_DEPLOY_MODE=hosted
IAGENT_AUTH_MODE=github
IAGENT_INVITE_REQUIRED=true

DB_URL=jdbc:postgresql://<host>:5432/<db>?sslmode=require
DB_USER=<db-user>
DB_PASSWORD=<db-password>

JWT_SECRET=<long-random-secret-at-least-32-bytes>
GITHUB_CLIENT_ID=<github-oauth-client-id>
GITHUB_CLIENT_SECRET=<github-oauth-client-secret>
FRONTEND_URL=https://<frontend-domain>

DEEPSEEK_API_KEY=<deepseek-api-key>
DEEPSEEK_MODEL=deepseek-chat
DASHSCOPE_API_KEY=<dashscope-api-key>
DASHSCOPE_EMBEDDING_MODEL=text-embedding-v3
```

这些运行时 secret 不写入 GitHub Actions workflow。推荐在 Azure Container App 里配置，GitHub Actions 只负责更新镜像。

## 3. GitHub Secrets

`.github/workflows/azure-deploy.yml` 需要以下 GitHub Secrets：

| Secret | 说明 |
|---|---|
| `AZURE_CLIENT_ID` | GitHub OIDC federated credential 对应的 Azure App client id |
| `AZURE_TENANT_ID` | Azure tenant id |
| `AZURE_SUBSCRIPTION_ID` | Azure subscription id |
| `AZURE_RESOURCE_GROUP` | 资源组名 |
| `AZURE_ACR_NAME` | ACR 名称，不带 `.azurecr.io` |
| `AZURE_ACR_LOGIN_SERVER` | ACR 登录地址，如 `xxx.azurecr.io` |
| `AZURE_BACKEND_CONTAINER_APP_NAME` | 后端 Container App 名称 |
| `AZURE_STATIC_WEB_APPS_API_TOKEN` | Static Web Apps 部署 token |
| `VITE_API_BASE` | 前端构建时的 API 地址，如 `https://api.example.com/api` |

## 4. GitHub OIDC 登录 Azure

推荐使用 GitHub OIDC，而不是把 Azure 密码放进仓库。

Azure App Registration / Managed Identity 需要配置 federated credential，subject 通常为：

```text
repo:<github-owner>/<github-repo>:ref:refs/heads/main
```

并给它足够权限：

- ACR push 权限，例如 `AcrPush`
- Container App 更新权限，例如资源组上的 `Contributor`

## 5. 自动部署流程

当前 workflow 在以下场景触发：

- push 到 `main`
- 手动 `workflow_dispatch`

流程：

1. 编译 Java 后端。
2. 登录 Azure。
3. 构建后端 Docker 镜像。
4. 推送到 ACR，tag 包含 `github.sha` 和 `latest`。
5. 更新后端 Azure Container App 镜像。
6. 构建并部署 React 前端到 Azure Static Web Apps。

## 6. GitHub OAuth 配置

GitHub OAuth App 建议配置：

```text
Homepage URL:
https://<frontend-domain>

Authorization callback URL:
https://<backend-domain>/api/auth/github/callback
```

后端 `FRONTEND_URL` 必须与实际前端域名一致，否则 OAuth 成功后跳转地址不对。

## 7. 首次上线检查

部署后按顺序检查：

```bash
curl https://<backend-domain>/actuator/health
curl https://<backend-domain>/api/auth/config
```

然后打开前端，确认：

- 登录页显示邀请码输入框。
- GitHub OAuth 可以跳转并回调。
- 新用户必须使用邀请码。
- 已注册用户再次登录不再消耗邀请码。