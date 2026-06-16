# Retro Shooter - 复古2D弹幕射击游戏

一款经典横版飞机大战网页游戏，具备完整的线上排行榜和游戏回放功能。

## 系统架构

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Game Client   │────▶│  Game Server    │────▶│     Redis       │
│ (HTML5 Canvas)  │     │ (Spring Boot)   │     │  (ZSet 排行榜)  │
└─────────────────┘     └─────────────────┘     └────────┬────────┘
                                                         │
                                                         ▼
                                                 ┌─────────────────┐
                                                 │ Archive Service │
                                                 │     (Go)        │
                                                 └────────┬────────┘
                                                         │
                                                         ▼
                                                 ┌─────────────────┐
                                                 │   PostgreSQL    │
                                                 │ (冷数据归档)    │
                                                 └─────────────────┘
```

## 功能特性

### 🎮 游戏客户端
- **HTML5 Canvas** 渲染的复古风格飞机大战
- 流畅的横版弹幕射击玩法
- 多种敌机类型和关卡递进系统
- 道具系统（血量恢复、火力增强）
- 按键操作序列二进制序列化
- 每局结束自动上传得分和回放数据

### ⚙️ 游戏服务端 (Java Spring Boot)
- **二进制数据反序列化**：自定义紧凑二进制协议
- **三重反作弊验证**：
  1. 基础数据验证（分数、时间范围）
  2. 输入序列验证（帧号连续性、无冲突按键）
  3. 游戏逻辑模拟验证（重放计算分数偏差）
- **Redis ZSet 实时排行榜**：支持今日榜、本周榜、历史总榜
- 回放数据存储与查询 API

### 🗄️ 冷数据归档服务 (Go)
- **定时任务调度**：每天凌晨自动归档前一天数据
- **Redis → PostgreSQL** 数据迁移
- **gzip 压缩**：大幅减少存储空间
- **批量操作**：事务保证数据一致性
- **连接池管理**：高效数据库连接复用
- HTTP 管理接口：支持手动触发归档

## 快速开始

### 环境要求
- Docker 20.10+
- Docker Compose 2.0+

### 一键启动

```bash
# 复制环境变量配置
cp .env.example .env

# 启动所有服务
docker-compose up -d --build
```

### 访问地址
- 游戏页面：http://localhost/game/play/
- 排行榜：http://localhost/leaderboard/
- 回放查看：http://localhost/replay/?gameId={gameId}
- 后端 API：http://localhost:8080/api/
- 归档服务：http://localhost:8081/health

### 停止服务

```bash
docker-compose down
```

## 项目结构

```
cj9/
├── game-client/                 # 前端游戏客户端
│   ├── public/
│   │   ├── game/play/          # 游戏主页面
│   │   ├── leaderboard/        # 排行榜页面
│   │   └── replay/             # 回放查看页面
│   └── src/
│       ├── game.js             # 游戏核心逻辑
│       └── serializer.js       # 二进制序列化器
├── game-server/                 # Java 后端服务
│   ├── src/main/java/com/retroshooter/
│   │   ├── controller/         # REST API 控制器
│   │   ├── service/            # 业务逻辑服务
│   │   ├── entity/             # 数据实体
│   │   ├── dto/                # 数据传输对象
│   │   └── config/             # 配置类
│   ├── Dockerfile
│   └── pom.xml
├── archive-service/             # Go 归档微服务
│   ├── config/                 # 配置加载
│   ├── internal/
│   │   ├── redis/              # Redis 客户端
│   │   ├── postgres/           # PostgreSQL 客户端
│   │   └── scheduler/          # 任务调度器
│   ├── Dockerfile
│   ├── main.go
│   └── go.mod
├── docker-compose.yml           # Docker 编排
├── nginx.conf                   # Nginx 配置
└── README.md
```

## API 文档

### 游戏服务 API

#### 提交游戏数据
```
POST /api/game/submit
Content-Type: application/octet-stream
Body: 二进制序列化的游戏数据
```

#### 查询排行榜
```
GET /api/game/leaderboard?type={daily|weekly|all}&limit=100
```

#### 获取回放数据
```
GET /api/game/replay/{gameId}
Response: 二进制回放数据
```

#### 获取玩家最高分
```
GET /api/game/player/{playerId}/best
```

#### 健康检查
```
GET /api/game/health
```

### 归档服务 API

#### 健康检查
```
GET /health
```

#### 手动触发归档
```
POST /archive/run
POST /archive/run?date=2024-01-15
```

## 二进制序列化协议

### 数据格式 (Big-Endian)

| 字段 | 字节数 | 说明 |
|------|--------|------|
| 魔数 | 2 | 固定值 `0x5253` (RS) |
| 版本号 | 1 | 当前版本 `1` |
| 保留位 | 1 | 预留扩展 |
| gameId长度 | 1 | |
| gameId | 变长 | UTF-8 编码 |
| playerId长度 | 1 | |
| playerId | 变长 | UTF-8 编码 |
| 得分 | 4 | uint32 |
| 关卡 | 2 | uint16 |
| 击杀数 | 4 | uint32 |
| 游戏时长 | 4 | uint32 (毫秒) |
| 开始时间 | 8 | uint64 (Unix 毫秒) |
| 总帧数 | 4 | uint32 |
| 输入记录数 | 4 | uint32 |
| 输入序列 | 5×N | 每条输入占5字节 |

### 输入记录格式 (每条5字节)

| 字段 | 字节数 | 说明 |
|------|--------|------|
| 帧号 | 4 | uint32 |
| 按键掩码 | 1 | 位掩码 |

### 按键位掩码

| 位 | 按键 | 说明 |
|----|------|------|
| 0 | UP | 向上移动 |
| 1 | DOWN | 向下移动 |
| 2 | LEFT | 向左移动 |
| 3 | RIGHT | 向右移动 |
| 4 | FIRE | 射击 |
| 5-7 | - | 保留 |

## 反作弊机制

### 1. 基础数据验证
- 分数范围检查 (0 ~ 1,000,000)
- 游戏时间范围检查 (0 ~ 3600秒)
- 击杀数合理性检查 (0 ~ 10,000)

### 2. 输入序列验证
- 帧号严格递增
- 无冲突按键检测（如 UP+DOWN 同时按下）
- 射速合理性检查（最短发射间隔）

### 3. 游戏逻辑模拟
- 服务端根据输入序列重新模拟完整游戏过程
- 计算模拟分数与声称分数的偏差
- 偏差超过 30% 判定为作弊

## Redis 数据结构

### 排行榜 ZSet
```
game:leaderboard:daily:2024-01-15
game:leaderboard:weekly:2024-W03
game:leaderboard:all
```
- Score: 游戏得分
- Member: `{playerId}:{gameId}:{score}`

### 游戏详情 Hash
```
game:record:{gameId}
```
存储完整的游戏记录信息

### 回放数据 String
```
game:replay:{gameId}
```
存储二进制回放数据

## PostgreSQL 数据结构

### game_records_archive 表
```sql
CREATE TABLE game_records_archive (
    id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(64) UNIQUE NOT NULL,
    player_id VARCHAR(64) NOT NULL,
    score INTEGER NOT NULL,
    stage INTEGER,
    enemies_killed INTEGER,
    game_time INTEGER,
    start_time TIMESTAMP,
    input_sequence BYTEA,
    replay_data BYTEA,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    archive_date DATE NOT NULL
);
```

### archive_summary 表
```sql
CREATE TABLE archive_summary (
    id BIGSERIAL PRIMARY KEY,
    archive_date DATE UNIQUE NOT NULL,
    record_count INTEGER NOT NULL,
    total_score BIGINT NOT NULL,
    compressed_size BIGINT,
    original_size BIGINT,
    archived_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

## 开发指南

### 本地开发 - Java 后端

```bash
cd game-server
mvn spring-boot:run
```

### 本地开发 - Go 归档服务

```bash
cd archive-service
go run main.go

# 手动运行一次归档
go run main.go -once

# 归档指定日期
go run main.go -date 2024-01-15

# 清理7天前数据
go run main.go -clean 7
```

### 本地开发 - 前端

```bash
cd game-client
# 使用任意静态文件服务器
python3 -m http.server 8080
# 或
npx serve public
```

## 游戏操作

| 按键 | 功能 |
|------|------|
| ↑ / W | 向上移动 |
| ↓ / S | 向下移动 |
| ← / A | 向左移动 |
| → / D | 向右移动 |
| 空格 / J | 射击 |
| P | 暂停/继续 |

## 性能优化

- **二进制序列化**：相比 JSON 减少约 70% 数据传输量
- **变化记录**：输入序列只在按键状态变化时记录，而非每帧记录
- **Redis ZSet**：O(log N) 的插入和排名查询
- **连接池**：数据库连接复用，减少连接开销
- **批量操作**：归档服务使用批量插入提升性能
- **gzip 压缩**：冷数据压缩存储，节省约 60% 磁盘空间
- **LRU 淘汰**：Redis 配置内存上限和 LRU 淘汰策略

## 监控与运维

### 日志查看
```bash
# 查看所有服务日志
docker-compose logs -f

# 查看特定服务日志
docker-compose logs -f game-server
docker-compose logs -f archive-service
```

### 数据备份
```bash
# Redis 备份
docker exec retroshooter-redis redis-cli BGSAVE

# PostgreSQL 备份
docker exec retroshooter-postgres pg_dump -U retroshooter retroshooter > backup.sql
```

### 手动归档
```bash
# 归档昨天数据
curl -X POST http://localhost:8081/archive/run

# 归档指定日期
curl -X POST "http://localhost:8081/archive/run?date=2024-01-15"
```

## 许可证

MIT License
