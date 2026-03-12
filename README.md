# LeagueInsight - 英雄联盟战绩查询工具

## 项目概述

**LeagueInsight** 是一款英雄联盟战绩查询工具，通过读取英雄联盟客户端（LCU）数据，提供召唤师信息查询、战绩分析、用户标签生成、自动化游戏操作、AI 智能分析等功能。

本项目采用 **Java + Electron** 技术栈，后端使用 Spring Boot 3.x，前端使用 Vue 3 + TypeScript。

---

## 功能特性

### 📊 战绩查询
- **高低胜率高亮**：直观展示队友近期表现
- **MVP 显示**：快速识别大腿玩家
- **玩家标签**：自动标记连胜、连败、非排位玩家等
- **关系显示**：识别宿敌与好友

### 🔍 对局分析
- **预组队检测**：标记预先组队的玩家（开黑检测）
- **历史遭遇**：标记曾经遇见过的玩家
- **单场详情面板**：展示 10 名玩家的 KDA、经济、补刀、承伤、推塔、装备、技能与符文
- **海克斯强化识别**：竞技场等特殊队列自动切换为强化展示，区分不同稀有度

### 🎮 游戏进行中
- **实时玩家信息**：游戏开始后查看双方 10 名玩家完整信息
- **段位展示**：单排/灵活组排段位一目了然
- **近期战绩**：每位玩家近期对局快速预览
- **预组队标记**：自动识别开黑队伍

### 🤖 AI 分析
- **房间级 AI 判断**：在组队/排队阶段快速给出队友与对手的风险判断
- **整局 AI 复盘**：对局详情中一键生成整场胜负归因
- **单人 AI 复盘**：对任意参战玩家单独分析，判断其表现类型
- **数据证据驱动**：AI 结论结合 KDA、伤害占比、承伤占比、经济、参团率等数据生成

### ⚙️ 自动化辅助
- **自动匹配**：自动开始寻找对局
- **自动接受**：匹配成功后自动接受
- **自动 BP**：自动选择和禁用预设英雄

---

## 目录

1. [系统架构](#系统架构)
2. [技术栈](#技术栈)
3. [项目结构](#项目结构)
4. [核心模块](#核心模块)
5. [API 文档](#api-文档)
6. [数据模型](#数据模型)
7. [AI 功能配置](#ai-功能配置)
8. [自动化功能](#自动化功能)
9. [用户标签系统](#用户标签系统)
10. [构建与部署](#构建与部署)
11. [开发指南](#开发指南)

---

## 系统架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              LeagueInsight                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        Electron 桌面应用                               │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                     Vue 3 + TypeScript 前端                      │  │  │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │  │  │
│  │  │  │   Views     │  │  Components │  │   Stores    │             │  │  │
│  │  │  │  (页面视图) │  │  (组件库)   │  │ (Pinia状态) │             │  │  │
│  │  │  └─────────────┘  └─────────────┘  └─────────────┘             │  │  │
│  │  │                          │                                      │  │  │
│  │  │                          ▼                                      │  │  │
│  │  │  ┌─────────────────────────────────────────────────────────┐   │  │  │
│  │  │  │                  API Client (Axios)                      │   │  │  │
│  │  │  │                  WebSocket Client (STOMP)                │   │  │  │
│  │  │  └─────────────────────────────────────────────────────────┘   │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                      │                                      │
│                                      │ HTTP / WebSocket                     │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                      Spring Boot 3.2 后端                              │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                      Controller 层                               │  │  │
│  │  │  Summoner / Session / AI / Automation / UserTag / Config / ... │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                  │                                    │  │
│  │                                  ▼                                    │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                       Service 层                                │  │  │
│  │  │  LcuService / AiAnalysisService / AutomationService / ...      │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                  │                                    │  │
│  │                                  ▼                                    │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                    LCU 通信层                                   │  │  │
│  │  │  ┌───────────────────┐  ┌───────────────────┐                  │  │  │
│  │  │  │  LcuHttpClient    │  │ LcuWebSocketClient│                  │  │  │
│  │  │  │  (HTTP REST API)  │  │  (实时事件监听)   │                  │  │  │
│  │  │  └───────────────────┘  └───────────────────┘                  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                      │                                      │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     英雄联盟客户端 (LCU)                               │  │
│  │                     LeagueClientUx.exe                                │  │
│  │                     - 本地 HTTPS 服务                                  │  │
│  │                     - WebSocket 事件推送                               │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 技术栈

### 后端技术栈

| 技术 | 版本 | 说明 |
|-----|------|-----|
| **Java** | 21 | 支持 Virtual Threads |
| **GraalVM** | 21.0.10 | Native Image 编译 |
| **Spring Boot** | 3.2.0 | 核心框架 |
| **Spring WebSocket** | - | WebSocket 支持 |
| **OkHttp** | 4.12.0 | HTTP 客户端 |
| **Java-WebSocket** | 1.5.5 | WebSocket 客户端 |
| **Caffeine** | 3.1.8 | 高性能缓存 |
| **JNA** | 5.14.0 | Windows 进程 API 调用 |
| **Lombok** | - | 代码简化 |
| **Jackson** | - | JSON/YAML 序列化 |

### 前端技术栈

| 技术                   | 版本     | 说明            |
|----------------------|--------|---------------|
| **Electron**         | 28.0.0 | 桌面应用框架        |
| **Vue**              | 3.3.11 | 前端框架          |
| **TypeScript**       | 5.3.3  | 类型支持          |
| **Vite**             | 5.0.8  | 构建工具          |
| **Pinia**            | 2.1.7  | 状态管理          |
| **Vue Router**       | 4.2.5  | 路由管理          |
| **Axios**            | 1.6.2  | HTTP 客户端      |
| **STOMP.js**         | -      | WebSocket 客户端 |

---

## 项目结构

### 后端项目结构

```
league-insight-backend/
├── src/main/java/com/ekko/insight/
│   ├── RankAnalysisApplication.java    # 启动类
│   │
│   ├── config/                          # 配置类
│   │   ├── AppConfig.java               # 应用配置
│   │   ├── BeanConfig.java              # Bean 配置
│   │   ├── WebConfig.java               # Web 配置
│   │   └── WebSocketConfig.java         # WebSocket 配置
│   │
│   ├── constant/                        # 常量定义
│   │   ├── GameConstants.java           # 游戏常量（服务器、段位、队列映射）
│   │   └── QueueType.java               # 队列类型枚举
│   │
│   ├── controller/                      # REST 控制器
│   │   ├── SummonerController.java      # 召唤师 API
│   │   ├── SessionController.java       # 游戏会话 API
│   │   ├── AiController.java            # AI 分析 API
│   │   ├── AutomationController.java    # 自动化 API
│   │   ├── UserTagController.java       # 用户标签 API
│   │   ├── TagConfigController.java     # 标签配置 API
│   │   ├── FandomController.java        # Fandom 数据 API
│   │   ├── AssetController.java         # 游戏资源 API
│   │   └── ConfigController.java        # 配置 API
│   │
│   ├── service/                         # 业务服务
│   │   ├── LcuService.java              # LCU 核心服务
│   │   ├── LcuHttpClient.java           # LCU HTTP 客户端
│   │   ├── AiAnalysisService.java       # AI 分析服务
│   │   ├── AutomationService.java       # 自动化服务
│   │   ├── UserTagService.java          # 用户标签服务
│   │   ├── TagConfigService.java        # 标签配置服务
│   │   ├── FandomService.java           # Fandom 数据服务
│   │   └── AssetService.java            # 游戏资源服务
│   │
│   ├── model/                           # 数据模型
│   │   ├── Summoner.java                # 召唤师
│   │   ├── Rank.java                    # 段位
│   │   ├── MatchHistory.java            # 战绩
│   │   ├── GameDetail.java              # 对局详情
│   │   ├── SessionData.java             # 会话数据
│   │   ├── SessionSummoner.java         # 会话召唤师
│   │   ├── AIAnalysisResult.java        # AI 分析结果
│   │   ├── UserTag.java                 # 用户标签
│   │   └── ...                          # 其他模型
│   │
│   ├── websocket/                       # WebSocket
│   │   └── LcuWebSocketClient.java      # LCU WebSocket 客户端
│   │
│   ├── jna/                             # Windows API
│   │   ├── ProcessUtils.java            # 进程工具
│   │   ├── Kernel32.java                # Windows Kernel API
│   │   └── Ntdll.java                   # Windows NT API
│   │
│   └── exception/                       # 异常处理
│       ├── LcuException.java            # LCU 异常
│       └── GlobalExceptionHandler.java  # 全局异常处理
│
└── src/main/resources/
    └── application.yml                  # 应用配置
```

### 前端项目结构

```
league-insight-fronted/
├── src/
│   ├── main/                            # Electron 主进程
│   │   └── main.ts                      # 主进程入口
│   │
│   └── renderer/                        # 渲染进程（前端）
│       ├── main.ts                      # 入口文件
│       ├── App.vue                      # 根组件
│       │
│       ├── api/                         # API 层
│       │   ├── httpClient.ts            # HTTP 客户端
│       │   └── websocketClient.ts       # WebSocket 客户端
│       │
│       ├── components/                  # 组件
│       │   ├── layout/                  # 布局组件
│       │   │   ├── TitleBar.vue         # 标题栏
│       │   │   └── Sidebar.vue          # 侧边栏
│       │   ├── home/                    # 首页组件
│       │   │   ├── StatusCard.vue       # 状态卡片
│       │   │   └── QuickActions.vue     # 快捷操作
│       │   ├── gaming/                  # 游戏进行中组件
│       │   │   └── PlayerCard.vue       # 玩家卡片
│       │   ├── summoner/                # 召唤师组件
│       │   │   ├── MatchDetailModal.vue # 对局详情弹窗
│       │   │   └── StatDots.vue         # 数据点组件
│       │   └── userTag/                 # 标签组件
│       │       ├── UserTagCard.vue      # 标签卡片
│       │       └── FriendDisputeCard.vue# 好友冤家卡片
│       │
│       ├── views/                       # 页面视图
│       │   ├── HomeView.vue             # 首页
│       │   ├── GamingView.vue           # 游戏进行中
│       │   ├── SummonerView.vue         # 召唤师查询
│       │   ├── MatchHistoryView.vue     # 战绩记录
│       │   ├── AutomationView.vue       # 自动化设置
│       │   ├── SettingsView.vue         # 设置页面
│       │   ├── UserTagView.vue          # 用户标签
│       │   └── TagConfigView.vue        # 标签配置
│       │
│       ├── stores/                      # 状态管理
│       │   ├── index.ts                 # Store 入口
│       │   ├── game.ts                  # 游戏状态
│       │   ├── automation.ts            # 自动化状态
│       │   └── userTag.ts               # 用户标签状态
│       │
│       ├── router/                      # 路由配置
│       │   └── index.ts                 # 路由定义
│       │
│       ├── types/                       # 类型定义
│       │   └── api.ts                   # API 类型
│       │
│       └── assets/                      # 静态资源
│           └── styles/
│               └── main.css             # 全局样式
│
├── package.json                         # 项目配置
└── vite.config.ts                       # Vite 配置
```

---

## 核心模块

### 1. LCU 通信模块

LCU（League Client Update）是英雄联盟客户端的本地服务，提供 REST API 和 WebSocket 事件。

#### LcuHttpClient

负责与 LCU 的 HTTPS REST API 通信：

```java
// 自动发现客户端端口和认证令牌
// 通过 JNA 读取进程命令行参数
public class LcuHttpClient {
    // 自动连接
    public void connect();

    // GET 请求
    public <T> T get(String uri, Class<T> responseType);

    // POST 请求
    public <T> T post(String uri, Object body, Class<T> responseType);

    // PATCH 请求
    public <T> T patch(String uri, Object body, Class<T> responseType);

    // DELETE 请求
    public <T> T delete(String uri, Class<T> responseType);
}
```

#### LcuWebSocketClient

监听 LCU 实时事件：

```java
public class LcuWebSocketClient {
    // 连接 LCU WebSocket
    public void connect(AuthInfo authInfo);

    // 订阅事件
    public void subscribe(String uri);

    // 添加事件监听器
    public void addListener(Consumer<LcuEvent> listener);
}
```

**监听的主要事件：**
- `/lol-gameflow/v1/gameflow-phase` - 游戏阶段变化
- `/lol-champ-select/v1/session` - 选人会话变化
- `/lol-lobby/v2/lobby` - 大厅变化

### 2. AI 分析模块

提供基于 AI 的对局分析功能：

```java
@Service
public class AiAnalysisService {
    // 分析对局详情
    public AIAnalysisResult analyzeGameDetail(Long gameId, String mode, Integer participantId);

    // 分析房间级数据
    public AIAnalysisResult analyzeSessionData(SessionData sessionData, String mode);
}
```

**分析模式：**
- `overview` - 整局总览：分析尽力榜、犯罪榜、被爆、被连累
- `player` - 单人复盘：判断尽力/犯罪/被爆/被连累/正常发挥

### 3. 自动化模块

提供游戏自动化功能：

| 功能   | 说明           |
|------|--------------|
| 自动匹配 | 在大厅自动开始匹配    |
| 自动接受 | 匹配成功自动接受     |
| 自动选人 | 选人阶段自动选择预设英雄 |
| 自动禁人 | 选人阶段自动禁用预设英雄 |

### 4. 用户标签模块

根据玩家战绩自动生成标签：

**默认标签规则：**
| 标签 | 条件 |
|-----|------|
| 连胜王 | 5连胜以上 |
| 连胜中 | 3连胜以上 |
| 连败中 | 5连败以上 |
| 运势不佳 | 3连败以上 |
| 大乱斗玩家 | 近期 70% 以上是大乱斗 |
| 高手玩家 | 胜率≥60% 且 KDA≥3.0 |

### 5. 会话数据模块

游戏进行中获取双方队伍完整信息：

```java
public class SessionData {
    private String phase;           // 游戏阶段
    private String queueType;       // 队列类型
    private String typeCn;          // 模式中文名
    private Integer queueId;        // 队列 ID
    private List<SessionSummoner> teamOne;  // 我方队伍
    private List<SessionSummoner> teamTwo;  // 敌方队伍
}
```

---

## API 文档

### 基础 URL

```
http://127.0.0.1:8080/api/v1
```

### 召唤师 API

| 方法  | 端点                                   | 说明         |
|-----|--------------------------------------|------------|
| GET | `/summoner/me`                       | 获取当前召唤师    |
| GET | `/summoner/puuid/{puuid}`            | 按 PUUID 查询 |
| GET | `/summoner/name/{name}`              | 按名称查询      |
| GET | `/summoner/rank/{puuid}`             | 获取段位信息     |
| GET | `/summoner/matches/{puuid}`          | 获取战绩       |
| GET | `/summoner/matches-filtered/{puuid}` | 筛选战绩       |
| GET | `/summoner/game-detail/{gameId}`     | 获取单局详情     |
| GET | `/summoner/win-rate/{puuid}`         | 获取胜率统计     |

### 会话 API

| 方法   | 端点                            | 说明             |
|------|-------------------------------|----------------|
| GET  | `/session/game-state`         | 获取游戏状态         |
| GET  | `/session/phase`              | 获取游戏阶段         |
| GET  | `/session/data`               | 获取完整会话数据（10名玩家）|
| GET  | `/session/lobby`              | 获取大厅信息         |
| GET  | `/session/champion-select`    | 获取选人会话         |
| POST | `/session/matchmaking/start`  | 开始匹配           |
| POST | `/session/matchmaking/cancel` | 取消匹配           |
| POST | `/session/accept`             | 接受对局           |
| GET  | `/session/connected`          | 检查连接状态         |

### AI 分析 API

| 方法   | 端点                | 说明       |
|------|-------------------|----------|
| POST | `/ai/analyze`     | AI 分析对局 |
| DELETE | `/ai/cache`     | 清除分析缓存  |

**分析请求：**
```json
{
  "gameId": 123456,
  "mode": "overview",  // 或 "player"
  "participantId": 1   // 单人模式时使用
}
```

### 自动化 API

| 方法   | 端点                             | 说明      |
|------|--------------------------------|---------|
| GET  | `/automation/status`           | 获取自动化状态 |
| POST | `/automation/match/start`      | 启动自动匹配  |
| POST | `/automation/match/stop`       | 停止自动匹配  |
| POST | `/automation/accept/{enabled}` | 设置自动接受  |
| POST | `/automation/pick/{enabled}`   | 设置自动选人  |
| POST | `/automation/ban/{enabled}`    | 设置自动禁人  |

### 用户标签 API

| 方法  | 端点                        | 说明           |
|-----|---------------------------|--------------|
| GET | `/user-tag/name/{name}`   | 按名称获取标签      |
| GET | `/user-tag/puuid/{puuid}` | 按 PUUID 获取标签 |

### 标签配置 API

| 方法     | 端点                        | 说明     |
|--------|---------------------------|--------|
| GET    | `/tag-config`             | 获取所有配置 |
| POST   | `/tag-config`             | 保存配置列表 |
| POST   | `/tag-config/add`         | 添加配置   |
| PUT    | `/tag-config/{id}`        | 更新配置   |
| DELETE | `/tag-config/{id}`        | 删除配置   |
| POST   | `/tag-config/{id}/toggle` | 切换启用状态 |
| POST   | `/tag-config/reset`       | 重置为默认  |
| GET    | `/tag-config/defaults`    | 获取默认配置 |

### 配置 API

| 方法  | 端点                   | 说明       |
|-----|----------------------|----------|
| GET | `/config`            | 获取所有配置   |
| GET | `/config/{key}`      | 获取指定配置   |
| PUT | `/config/{key}`      | 更新配置     |
| GET | `/config/champions`  | 获取英雄列表   |
| GET | `/config/game-modes` | 获取游戏模式列表 |

---

## 数据模型

### SessionData（会话数据）

```typescript
interface SessionData {
  phase: string              // 游戏阶段
  queueType: string          // 队列类型
  typeCn: string             // 模式中文名
  queueId: number            // 队列 ID
  teamOne: SessionSummoner[] // 我方队伍
  teamTwo: SessionSummoner[] // 敌方队伍
}

interface SessionSummoner {
  championId: number
  championKey: string
  summoner: Summoner
  matchHistory: MatchHistory[]
  userTag: UserTag
  rank: Rank
  meetGames: OneGamePlayer[]      // 遇到过的玩家
  preGroupMarkers: PreGroupMarker // 预组队标记
  isLoading: boolean
}
```

### GameDetail（对局详情）

```typescript
interface GameDetail {
  gameId: number
  gameMode: string
  queueId: number
  gameDuration: number
  gameCreation: number
  participants: GameParticipant[]
  participantIdentities: GameParticipantIdentity[]
}

interface GameStats {
  win: boolean
  kills: number
  deaths: number
  assists: number
  goldEarned: number
  totalDamageDealtToChampions: number
  totalDamageTaken: number
  totalHeal: number
  // 符文
  perk0?: number
  perk1?: number
  // 海克斯强化 (竞技场模式)
  playerAugment1?: number
  playerAugment2?: number
  playerAugment3?: number
  playerAugment4?: number
}
```

### AIAnalysisResult（AI 分析结果）

```typescript
interface AIAnalysisResult {
  success: boolean
  content?: string   // Markdown 格式分析内容
  error?: string
}
```

### Summoner（召唤师）

```typescript
interface Summoner {
  gameName: string        // 游戏名称
  tagLine: string         // 标签行
  summonerLevel: number   // 等级
  profileIconId: number   // 头像 ID
  puuid: string           // PUUID
  summonerId: number      // 召唤师 ID
}
```

---

## AI 功能配置

### 配置项

```yaml
app:
  settings:
    ai:
      enabled: true                                              # 开启 AI 功能
      endpoint: https://ai.nuliyangguang.top                    # AI API 端点
      model: qwen-turbo                                          # 模型名称
      apiKey: your-api-key                                       # API Key（可选）
```

### 默认配置

| 配置项 | 默认值 |
|-------|-------|
| endpoint | `https://ai.nuliyangguang.top` |
| model | `qwen-turbo` |

### API 请求格式

服务会发送 OpenAI 兼容格式请求：

```json
{
  "model": "qwen-turbo",
  "messages": [
    {"role": "system", "content": "你是一个LOL游戏分析师..."},
    {"role": "user", "content": "对局数据分析提示词..."}
  ]
}
```

### 分析结果示例

```markdown
## 总体结论
- 本局蓝方凭借中野联动取得前期优势，最终 28 分钟结束比赛。

## 尽力榜
- 张三#CN1 (尽力): KDA 8/2/10，伤害占比 32%，参团率 85%
- 李四#CN2 (尽力): KDA 5/3/15，承伤占比 28%

## 犯罪榜
- 本局无人明显犯罪

## 关键证据
- 蓝方经济领先 8k，大龙控制率 100%
- 红方下路组合发育不良，伤害占比仅 18%
```

---

## 自动化功能

### 工作流程

```
┌─────────────────────────────────────────────────────────────────┐
│                      自动化工作流程                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐ │
│  │ 自动匹配 │───▶│ 自动接受 │───▶│ 自动禁人 │───▶│ 自动选人 │ │
│  │          │    │          │    │          │    │          │ │
│  │ Lobby    │    │ReadyCheck│    │ChampSelect│   │ChampSelect│ │
│  │ 阶段    │    │ 阶段    │    │ Ban阶段  │    │ Pick阶段 │ │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘ │
│       │               │               │               │        │
│       ▼               ▼               ▼               ▼        │
│  POST /search    POST /accept   PATCH /ban     PATCH /pick    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 配置示例

```yaml
app:
  settings:
    auto:
      startMatchSwitch: true        # 启用自动匹配
      acceptMatchSwitch: true       # 启用自动接受
      pickChampionSwitch: true      # 启用自动选人
      banChampionSwitch: true       # 启用自动禁人
      pickChampionSlice: [157, 92]  # 优先选择的英雄 ID
      banChampionSlice: [238, 555]  # 优先禁用的英雄 ID
```

---

## 用户标签系统

### 条件树结构

```
TagCondition (条件树)
│
├── AndCondition
│   ├── type: "and"
│   └── conditions: TagCondition[]
│
├── OrCondition
│   ├── type: "or"
│   └── conditions: TagCondition[]
│
├── NotCondition
│   ├── type: "not"
│   └── condition: TagCondition
│
└── HistoryCondition
    ├── type: "history"
    ├── filter: MatchFilter        # 过滤器
    └── refresh: MatchRefresh      # 刷新器
```

### 过滤器类型

| 类型             | 字段                    | 说明        |
|----------------|-----------------------|-----------|
| QueueFilter    | queueId               | 按队列 ID 过滤 |
| ChampionFilter | championId            | 按英雄 ID 过滤 |
| StatFilter     | stat, operator, value | 按统计数据过滤   |

### 刷新器类型

| 类型             | 说明    |
|----------------|-------|
| CountRefresh   | 计数    |
| AverageRefresh | 平均值   |
| SumRefresh     | 求和    |
| MaxRefresh     | 最大值   |
| MinRefresh     | 最小值   |
| StreakRefresh  | 连胜/连败 |

---

## 构建与部署

### 环境要求

| 工具 | 版本 | 说明 |
|-----|------|-----|
| **GraalVM JDK 21** | 21.0.10+ | 用于 Native Image 编译 |
| **Visual Studio Build Tools** | 2022+ | Windows 编译工具（需安装 C++ 桌面开发工作负载） |
| **Node.js** | 18+ | 前端构建 |
| **Maven** | 3.9+ | 后端构建 |

### 一键构建

```bash
# 在项目根目录运行
build.bat
```

该脚本会自动完成：
1. 检查环境（GraalVM、Maven、Node.js）
2. 初始化 MSVC 编译环境
3. 编译 Native Image 后端
4. 构建前端 Electron 应用

### 后端构建

#### 方式一：Native Image（推荐）

Native Image 编译为独立可执行文件，无需 JRE，启动更快。

```bash
# 进入后端目录
cd league-insight-backend

# 运行 Native Image 构建脚本
build-native.bat

# 或手动构建（需先初始化 MSVC 环境）
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set JAVA_HOME=C:\path\to\graalvm-jdk-21
mvn package -Pnative -DskipTests
```

**输出文件：** `target/league-insight-native.exe`（约 95MB）

#### 方式二：JAR 包（开发调试）

```bash
# Maven 构建
mvn clean package -DskipTests

# 运行 JAR（需要 JRE 21）
java --enable-preview -jar target/league-insight-backend-1.0.0.jar
```

### 前端构建

```bash
# 进入前端目录
cd league-insight-fronted

# 安装依赖
npm install

# 开发模式
npm run electron:dev

# 构建 Windows 安装包
npm run electron:build
```

### 打包产物

```
release/
├── LeagueInsight Setup 1.0.0.exe   # Windows 安装包（约 103MB）
└── win-unpacked/                   # 免安装版本
    ├── LeagueInsight.exe           # 主程序
    └── resources/
        ├── app.asar                # 前端资源
        ├── backend/                # 后端程序
        │   └── league-insight-backend.exe  # Native Image 后端
        └── public/                 # 静态资源
```

### Native Image 优势

| 特性 | Native Image | JAR 包 |
|-----|-------------|-------|
| 启动时间 | ~0.1 秒 | ~3 秒 |
| 内存占用 | 更低 | 较高 |
| 依赖 | 无需 JRE | 需要 JRE 21 |
| 文件大小 | ~95MB | ~29MB JAR + JRE |

### GraalVM 配置

Native Image 相关配置位于：
- `pom.xml` - native profile 配置
- `src/main/resources/META-INF/native-image/native-image.properties` - GraalVM 配置

### 常见构建问题

**Q: Native Image 编译失败 - 找不到 cl.exe？**

确保已安装 Visual Studio Build Tools，并选择 "使用 C++ 的桌面开发" 工作负载。

**Q: 端口被占用导致测试失败？**

关闭占用 8080 端口的进程后重试：
```bash
netstat -ano | findstr :8080
taskkill /F /PID <PID>
```

---

## 开发指南

### 环境要求

| 工具 | 版本 | 说明 |
|-----|------|-----|
| **GraalVM JDK 21** | 21.0.10+ | 用于 Native Image 编译 |
| **Visual Studio Build Tools** | 2022+ | Windows 编译工具 |
| **Node.js** | 18+ | 前端构建 |
| **Maven** | 3.9+ | 后端构建 |
| **Windows** | 10/11 | 仅支持 Windows |

### 本地开发

1. **启动后端**
   ```bash
   cd league-insight-backend
   mvn spring-boot:run
   ```

2. **启动前端**
   ```bash
   cd league-insight-fronted
   npm run electron:dev
   ```

### 代码规范

- 后端使用 Lombok 简化代码
- 前端使用 TypeScript 严格模式
- API 响应使用标准 JSON 格式
- 日志级别：生产 INFO，开发 DEBUG

---

## 常见问题

### Q: 无法连接 LCU？

1. 确保英雄联盟客户端正在运行
2. 检查是否有多个客户端进程
3. 重启应用程序

### Q: 自动化不生效？

1. 检查自动化开关是否启用
2. 确认当前游戏阶段
3. 查看日志排查错误

### Q: AI 分析失败？

1. 检查 AI 配置是否正确
2. 确认网络连接正常
3. 查看后端日志获取详细错误信息

### Q: 游戏进行中页面无数据？

1. 确认已进入选人或游戏阶段
2. 点击刷新按钮重新获取
3. 检查后端日志确认 API 调用是否成功

---

## 版本历史

| 版本 | 日期 | 更新内容 |
|-----|------|---------|
| 1.0.0 | 2026-03 | 初始版本 |
| 1.1.0 | 2026-03 | 新增 AI 分析功能、游戏进行中页面、海克斯强化识别 |
| 1.2.0 | 2026-03 | 改用 GraalVM Native Image 编译，无需 JRE 独立运行 |

---

## 许可证

MIT License

---

## 作者

ekko
