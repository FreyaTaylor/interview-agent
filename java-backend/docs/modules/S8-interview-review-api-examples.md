# S8 Interview API 调用样例（POST + body）

基础地址：`http://localhost:8080`

## 1) preview-parse

```bash
curl -X POST 'http://localhost:8080/api/interview/preview-parse' \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "面试官：你讲讲 HashMap 扩容。\n我：JDK8 之后采用尾插并在阈值达到时 resize。",
    "company": "字节",
    "position": "Java 后端"
  }'
```

## 2) finalize

```bash
curl -X POST 'http://localhost:8080/api/interview/finalize' \
  -H 'Content-Type: application/json' \
  -d '{
    "turns": [
      {"id": 1, "speaker": "面试官", "content": "你讲讲 HashMap 扩容"},
      {"id": 2, "speaker": "我", "content": "JDK8 之后采用尾插并在阈值达到时 resize"}
    ],
    "groups": [
      {
        "type": "knowledge",
        "tag": "HashMap",
        "turn_ids": [1,2],
        "questions": ["你讲讲 HashMap 扩容"],
        "user_answer": "JDK8 之后采用尾插并在阈值达到时 resize",
        "original_dialogue": "面试官：你讲讲 HashMap 扩容\\n我：JDK8 之后采用尾插并在阈值达到时 resize"
      }
    ],
    "company": "字节",
    "position": "Java 后端"
  }'
```

## 3) parse（一步直解）

```bash
curl -X POST 'http://localhost:8080/api/interview/parse' \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "面试官：讲讲你做过的秒杀系统。\n我：我们用 Redis + MQ 做削峰。",
    "company": "美团",
    "position": "后端开发"
  }'
```

## 4) check-duplicate

```bash
curl -X POST 'http://localhost:8080/api/interview/check-duplicate' \
  -H 'Content-Type: application/json' \
  -d '{
    "text_hash": "a1b2c3d4e5f6..."
  }'
```

## 5) overwrite

```bash
curl -X POST 'http://localhost:8080/api/interview/overwrite' \
  -H 'Content-Type: application/json' \
  -d '{
    "record_id": 101
  }'
```

## 6) draft

```bash
curl -X POST 'http://localhost:8080/api/interview/draft' \
  -H 'Content-Type: application/json' \
  -d '{
    "record_id": 101,
    "turns": [
      {"id": 1, "speaker": "面试官", "content": "介绍一下你最近的项目"},
      {"id": 2, "speaker": "我", "content": "我做的是订单履约系统"}
    ],
    "groups": [
      {
        "type": "project",
        "tag": "订单系统",
        "turn_ids": [1,2],
        "questions": ["介绍一下你最近的项目"],
        "user_answer": "我做的是订单履约系统",
        "original_dialogue": "面试官：介绍一下你最近的项目\\n我：我做的是订单履约系统"
      }
    ],
    "company": "阿里",
    "position": "高级后端"
  }'
```

## 7) history-list

```bash
curl -X POST 'http://localhost:8080/api/interview/history-list' \
  -H 'Content-Type: application/json' \
  -d '{}'
```

## 8) history-detail

```bash
curl -X POST 'http://localhost:8080/api/interview/history-detail' \
  -H 'Content-Type: application/json' \
  -d '{
    "record_id": 101
  }'
```

## 9) history-recalibrate（删旧建新）

```bash
curl -X POST 'http://localhost:8080/api/interview/history-recalibrate' \
  -H 'Content-Type: application/json' \
  -d '{
    "record_id": 101,
    "turns": [
      {"id": 1, "speaker": "面试官", "content": "你项目里怎么做幂等"},
      {"id": 2, "speaker": "我", "content": "我们用业务唯一键 + 去重表"}
    ],
    "groups": [
      {
        "type": "project",
        "tag": "幂等",
        "turn_ids": [1,2],
        "questions": ["你项目里怎么做幂等"],
        "user_answer": "我们用业务唯一键 + 去重表",
        "original_dialogue": "面试官：你项目里怎么做幂等\\n我：我们用业务唯一键 + 去重表"
      }
    ]
  }'
```

## 10) history-delete

```bash
curl -X POST 'http://localhost:8080/api/interview/history-delete' \
  -H 'Content-Type: application/json' \
  -d '{
    "record_id": 101
  }'
```

## 11) history-update-meta

```bash
curl -X POST 'http://localhost:8080/api/interview/history-update-meta' \
  -H 'Content-Type: application/json' \
  -d '{
    "record_id": 101,
    "company": "腾讯",
    "position": "后端开发"
  }'
```

## 返回体约定

成功：

```json
{"code":0,"data":{...},"message":"success"}
```

失败：

```json
{"code":40001,"data":null,"message":"..."}
```
