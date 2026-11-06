#!/usr/bin/env node
/**
 * 一键打包：直接调用 electron-builder CLI。
 * 不走 npm run / .bin 链接，因为该环境安装 .bin 软链偶发缺失。
 * 用法：node scripts/pack.mjs [--win nsis|portable|dir]
 */
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const CLI = path.join(ROOT, 'node_modules', 'electron-builder', 'out', 'cli', 'cli.js')

if (!existsSync(CLI)) {
  console.error('找不到 electron-builder，请先在 desktop 目录执行 npm install')
  process.exit(1)
}

// 国内镜像：Electron 二进制 + NSIS/winCodeSign 等打包工具
process.env.ELECTRON_MIRROR ||= 'https://npmmirror.com/mirrors/electron/'
process.env.ELECTRON_BUILDER_BINARIES_MIRROR ||= 'https://npmmirror.com/mirrors/electron-builder-binaries/'

const args = process.argv.slice(2)
const target = args.length ? args : ['--win', 'nsis']

console.log('打包目标:', target.join(' '))
execFileSync(process.execPath, [CLI, ...target], { cwd: ROOT, stdio: 'inherit' })
console.log('完成，产物位于 desktop/dist/')
