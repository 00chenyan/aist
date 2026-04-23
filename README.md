<div align="center">

# AIST
### AI-assisted Impact Scope Tool

**Analysis of Impact Scope of Task**

A Spring Boot service for intelligent **impact analysis** on requirements or code changes, powered by the DeepSeek API and a local tool chain; includes a web UI for tasks and batch analysis.

<br/>

[![Java](https://img.shields.io/badge/Java-17-437291?style=flat&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?style=flat&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-5.7%20%7C%208-4479A1?style=flat&logo=mysql&logoColor=white)](https://www.mysql.com/)
[![License](https://img.shields.io/badge/License-See%20repo-9E9E9E?style=flat)](#license)

<br/>

</div>

---

## ✨ Features

| Area | Description |
|:---|:---|
| 📋 **Requirements** | CRUD on `requirement`; filter enabled only, soft delete (`enable=0`), update status from analysis, etc. |
| 🔍 **Code analysis** | LLM + pluggable tools: search repo, call chains, methods / Mapper XML / config / Git diffs, target DB schema; **JSON** or **SSE** streaming. |
| 💬 **Multi-turn** | Create/clear `sessionId`; in-memory context (~30 min TTL); some data persisted in `conversation_record`. |

### 💬 Prompt language

> **System / tool prompts and scenario text** in this repo are written in **Chinese** by default. For an English UI or model output, change them in Java and static assets as you prefer.

---

## 🧩 Tech stack

| Layer | Technology |
|:---:|:---|
| ☕ Runtime | Java 17, Spring Boot 3.3.x |
| 🌐 Web | Spring Web, SSE (`text/event-stream`) |
| 🗄️ Persistence | MyBatis-Plus 3.5, MySQL |
| 🤖 AI | DeepSeek API (OkHttp) |
| 📐 Parsing | JavaParser, Eclipse JDT Core |
| 📦 Other | Lombok, FastJSON2, Springfox Swagger2 (annotations) |

---

## 📋 Requirements

- ☕ **JDK** 17+  
- 🔨 **Maven** 3.6+, or project **`mvnw` / `mvnw.cmd`**  
- 🐬 **MySQL** 5.7+ / 8.x, with a database created first (default name: `aist`)  

### 🗃️ Database setup

Schema file: **[`sql/schema.sql`](sql/schema.sql)**

| Table | Role |
|:---|:---|
| `requirement` | Tasks, Git commit, analysis output, status, soft-delete flag `enable`, etc. |
| `conversation_record` | Session rows: `session_id`, question/session types, `content`, timestamps, optional thumbs, etc. |

```bash
# Run after creating the database
mysql -h127.0.0.1 -P3306 -uroot -p aist < sql/schema.sql
```

*Works in Windows PowerShell too.* Entities: `com.aist.entity.Requirement`, `com.aist.model.ConversationRecord`. Keep mappers in sync if you extend the schema.

---

## ⚙️ Configuration

File: **`src/main/resources/application.yml`**

| Key | Purpose |
|:---|:---|
| `spring.datasource` | App database (business + conversation data) |
| `deepseek.api.key` | DeepSeek key — **do not commit real secrets**; use env vars in production |
| `aist.target-db` | DB of the **project under analysis** (schema, etc.); can match the app DB |
| `aist.code-repo.path` | **Root path** of the local code repo (must exist and be readable) |

> 🔒 **Security:** map secrets from the environment to `deepseek.api.key` instead of hard-coding.

Optional: set `server.port` to override the default **8080**.

---

## 🚀 Build & run

```bash
# Build
mvn clean package -DskipTests

# Run (from repo root)
mvn spring-boot:run
```

**Windows (Maven Wrapper)**

```bash
.\mvnw.cmd spring-boot:run
```

Web UI: [`http://localhost:8080/index-iact.html`](http://localhost:8080/index-iact.html) (served from `src/main/resources/static`).

---

## 🔌 API overview

| Method | Path | Description |
|:---:|:---|:---|
| `POST` | `/code/analyze/sync` | Synchronous analysis → JSON |
| `POST` | `/code/analyze/stream` | Streaming analysis (SSE) |
| `POST` | `/code/analyze/session/create` | New session, returns `sessionId` |
| `DELETE` | `/code/analyze/session/{sessionId}` | Clear server-side session |
| `GET` | `/requirements` | List; `?onlyEnabled=true` for enabled only |
| `GET` | `/requirements/{id}` | Get by id |
| `POST` | `/requirements` | Create |
| `PUT` | `/requirements/{id}` | Update |
| `POST` | `/requirements/{id}/delete` | Soft delete |

**Body** (`CodeAnalyzeRequest`): `projectId`, `apiUrl` (optional), `question` (required), `sessionId` (optional, multi-turn).

---

## 🛠️ Built-in tools

The model issues `[TOOL_CALL:TOOL_NAME:args]`; tools are registered at startup in `ToolRegistry`.

| Tool | What it does |
|:---|:---|
| `SEARCH_FILE` | Search file contents in the repo |
| `SEARCH_CONFIG` | Search config (e.g. `application.yml`) |
| `SEARCH_MAPPER_XML` | Search MyBatis Mapper XML |
| `VIEW_METHOD` | Show Java method source |
| `TRACE_CALL_CHAIN` | Call-chain tracing |
| `GIT_DIFF` | Git diffs |
| `DATABASE` | Inspect target DB table schema, etc. |

---

## License

See the license file in this repository, if any; otherwise follow the maintainers’ terms.
