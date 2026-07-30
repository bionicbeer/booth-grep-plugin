# booth-grep

![CI](https://github.com/bionicbeer/booth-grep/actions/workflows/ci.yaml/badge.svg)
![Release](https://img.shields.io/github/v/release/bionicbeer/booth-grep?label=version&sort=semver)

booth-grep - Halo 插件

## 简介

这是一个基于 Halo 的插件项目。

## 开发环境

- Java 21+
- Node.js 18+
- pnpm

## 开发

```bash
# 启用插件
./gradlew haloServer
# 开发前端
cd ui
pnpm install
pnpm dev
```

## 构建

```bash
./gradlew build
```

构建完成后，可以在 `build/libs` 目录找到插件 jar 文件。

## 许可证

[GPL-3.0](./LICENSE) © bionicbeer 