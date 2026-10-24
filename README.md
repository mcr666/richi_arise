# 立直浮现之时 (riichi_arise)

Minecraft NeoForge 1.21.1 模组，从 `D:\Code\MurmolMod` 的麻雀（日麻）模块迁移而来，使用 MCreator 工作区。

| 项 | 值 |
|---|---|
| modid / 资源命名空间 | `riichi_arise` |
| 显示名 | 立直浮现之时 |
| Java 包 | `mcr.riichi_arise` |
| 版本 | 1.0.0 |
| NeoForge | 21.1.232（MC 1.21.1） |

---

## 1. 目录结构

```
riichi_arise/
├─ src/main/java/mcr/riichi_arise/      # 全部源码（包名 = mcr.riichi_arise）
│   ├─ RichiiAriseMod.java              # @Mod 主类（注册表 / 网络包 / TLM 联动入口）
│   ├─ Mahjong*.java                    # 牌 / 牌桌方块 / 命令 / 战利品 / 粒子 / 物品栏
│   ├─ block/                           # FengPanBlock（风盘，对局核心方块）
│   ├─ client/                          # 客户端界面与假玩家渲染
│   ├─ compat/                          # TLM（TouhouLittleMaid）软依赖联动
│   ├─ game/                            # 大厅 / 状态 / 同步 / 存档
│   │   └─ riichi/                      # 对局逻辑、AI、向听与危险度算法
│   ├─ mixin/                           # Mixin（仅 MusicManagerMixin）
│   ├─ network/                         # 网络包
│   ├─ render/                          # 桌面几何与摆位（TableLayout）
│   └─ voice/                           # 角色语音（内置 mp3 播放器）
├─ src/main/resources/
│   ├─ META-INF/neoforge.mods.toml      # mod 元数据 + 依赖（含 TLM optional）
│   ├─ META-INF/accesstransformer.cfg   # AT
│   ├─ riichi_arise.mixins.json
│   ├─ assets/riichi_arise/             # 模型 / 贴图 / 语言 / 粒子 / 语音 mp3
│   └─ data/riichi_arise/               # 配方 / 战利品表
└─ README.md                            # 本文档
```

## 2. 构建与运行

沙箱内 Gradle 不可用，改用 **javac 直编 + DevLaunch 启动**。

### 依赖

| 依赖 | 用途 | 打包方式 |
|---|---|---|
| `com.github.mahjong4j:mahjong4j:0.3.2` | 和牌 / 役种 / 点数判定 | 解包进 mod |
| `com.googlecode.soundlibs:jlayer:1.0.1.4` | mp3 解码（角色语音） | 同上 |
| `maven.modrinth:touhou-little-maid` | 女仆联动 API | `compileOnly`（软依赖，不打包） |

## 3. 功能概览

### 3.1 牌桌与对局
- 风盘方块（`FengPanBlock`）即一张雀桌：右击开管理界面、潜行右击进排队，`deal` 开局。
- 对局逻辑在 `game/riichi/RiichiGame` + `RiichiWall` + `RichiTableState`，AI 在 `RiichiBot`。
- 牌桌摆位由 `render/TableLayout` 统一计算。

### 3.2 角色与语音
- 新增角色「一姬」（野猪流）。
- 角色语音预热机制，消除首次播放延迟。
- AI 座位使用原版玩家模型，支持本地皮肤贴图。

### 3.3 TLM 联动
- 软依赖联动，未安装 TLM 时自动跳过。
- 提供女仆雀士任务与好感度结算。
