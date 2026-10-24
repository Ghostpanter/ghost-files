# ghost-files 0.7.2-test1（草稿测试包）

versionCode **30** · 未打 tag · **未真机验证**

## 本包改动

### 提权层级 Root > Shizuku > 普通用户
- 高层级覆盖低层级；侧栏「提权访问」文案按矩阵显示，不再对已被覆盖的低层级提示「未授权 / 点此授权」。
- **Root 已授权**：Shizuku 条显示「Root 已授权 · Android/data、obb」；点入用 Root 后端浏览 Android/data、obb。安装来源 / 空间分析等原走 Shizuku 的能力在有 Root 时优先 Root。
- **仅 Shizuku（无 Root）**：Root 条显示「Shizuku 已授权 · 无 Root（shell 权限）」；Shizuku 条按外置 / 内置显示已授权。
- **都没有**：才显示未授权与授权入口。

### 重新授权
- 最高层级那一条：右侧 ⋮ 或长按 →「重新授权」  
  - Root：清除 su 缓存并重新申请  
  - Shizuku：强制重新请求权限 / 重启内置 PrivilegeServer  
- 被覆盖的低层级可在菜单中「单独授权」（无强提示弹窗）。

### 测试
- 单测：层级选择、侧栏文案状态矩阵（Root / Shizuku / 内置 / 无）
- 侧栏快照：Root 已授权、仅 Shizuku 两种状态

## 打包
compile/targetSdk 34 · minSdk 28 · Ghostpanter V1+V2 · extractNativeLibs=true · 16KB zipalign · 4 ABI

## 未真机验证
- Root 覆盖后侧栏文案与点入 Android/data 行为
- 仅 Shizuku 时 Root 条文案
- 重新授权 / 菜单单独授权低层级
- Root 优先跑安装来源、空间分析等提权路径
