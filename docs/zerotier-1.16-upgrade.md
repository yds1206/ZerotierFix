# 升级 ZeroTier 内核到 1.16.2 并保留自建 Planet 支持

本文记录 ZerotierFix 内核升级到 ZeroTierOne 1.16.2 的改动、构建方式，以及自建 Planet / 自建控制器的配置流程。

## 1. 改动概览

| 项目 | 改动前 | 改动后 |
| --- | --- | --- |
| 内核版本 | ZeroTierOne 1.12.2（kaaass fork） | ZeroTierOne 1.16.2（官方 tag `1.16.2`） |
| 内核来源 | git submodule `externals/core` | 同路径，改为官方仓库 |
| 原生构建 | `externals/core/java/CMakeLists.txt` | `core/src/main/cpp/CMakeLists.txt`（不再修改 submodule） |
| SDK Java 源码 | 通过 git 符号链接引入（Windows 下失效） | `core/build.gradle` 中用 `sourceSets` 显式引入 |
| NDK | 未固定 | 26.3.11579264（r26d） |
| 自建 Planet | DataStore 拦截 `planet` 文件 | 保持不变，并新增格式校验 |

自建 Planet 的核心原理没有变化：Java 层的 `DataStore` 实现 `DataStoreGetListener` / `DataStorePutListener`，
当内核按规范路径 `planet` 读取世界（World）状态时，返回用户导入的 `planet.custom`；
同时拦截写入和删除，避免自定义 Planet 被内核覆盖。

## 2. Planet 文件格式不兼容（重要）

ZeroTier 1.16 改变了 World 的序列化格式：

- 1.12：`updatesMustBeSignedBy` 32 字节（C25519 公钥），签名 64 字节。
- 1.16：`updatesMustBeSignedBy` 64 字节（C25519 + Ed25519 公钥集合），签名 96 字节。

因此 **旧版生成的 planet 文件无法被 1.16 内核解析**。内核解析失败会静默回退到官方 Planet，
表现为"设置了自定义 Planet 但仍然连的是官方网络"。

本版本在导入时会解析并校验新的二进制布局：

- 合法：移动到 `files/planet.custom`，设置页会显示 Planet ID。
- 旧版/损坏：提示 `planet_incompatible`，不会写入。

### 用 1.16.2 重新生成 Planet

在已经部署自建控制器（如 ztncui）的服务器上，使用 1.16.2 的 `zerotier-idtool`：

```bash
# planet.json 里 id 为自建 Planet 的 ID，roots 为 moon/root 节点身份与地址
zerotier-idtool genmoon planet.json
```

`genmoon` 支持 `worldType: "planet"`，生成的文件名为 `<planet-id>.moon`，把它导入 App 即可。
注意：必须使用 **1.16 及以上版本** 的 `zerotier-idtool`，旧版本生成的文件会被 App 拒绝。

一个最小可用的 `planet.json` 结构：

```json
{
  "objtype": "world",
  "worldType": "planet",
  "id": "0x0000000000000000",
  "updatesMustBeSignedBy": "<64 字节十六进制公钥>",
  "signingKey": "<64 字节十六进制公钥>",
  "signingKey_SECRET": "<64 字节十六进制私钥>",
  "roots": [
    {
      "identity": "<root 节点身份字符串>",
      "stableEndpoints": ["1.2.3.4/9993"]
    }
  ]
}
```

## 3. 构建

环境要求：

- JDK 17（AGP 7.4.2 不再支持 JDK 8）
- Android SDK Platform 33、Build-Tools 30.0.3
- NDK 26.3.11579264（r26d）
- CMake 3.22.1

```bash
git submodule update --init --recursive
./gradlew :app:assembleDebug
```

`local.properties` 中的 `sdk.dir` 需指向本机 SDK。Windows 上如果 `core/src/main/java` 是普通文件
（git 符号链接在未开启 `core.symlinks` 时会被检出为文本文件），现在的 `sourceSets` 配置已经绕开了它，
无需手动处理。

## 4. 验证

- `:core:buildCMakeDebug[arm64-v8a]` 编译并链接 `libZeroTierOneJNI.so` 成功。
- `:app:testDebugUnitTest --tests net.kaaass.zerotierfix.util.PlanetFileTest` 通过（4 个用例：
  正常解析、截断文件、1.16 之前格式、root 数量越界）。
- `:app:assembleDebug` 产出可安装 APK，`lib/arm64-v8a/libZeroTierOneJNI.so` 导出全部
  `Java_com_zerotier_sdk_Node_*` JNI 符号。

## 5. 已知限制

- 1.16 内核要求的 `ZT_Node_Config` 由 JNI 默认构造（`enableEncryptedHello=0`、`lowBandwidthMode=0`），
  与原 App 行为一致。
- 目前只验证了构建与单元测试；实际组网（加入自建控制器网络）需要在设备上安装后验证。
