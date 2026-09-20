# share2znas (open2share + WebDAV)

> 这是 [linesoft2/open2share](https://github.com/linesoft2/open2share) 的分支：保留原有的“打开 → 分享”转换功能，并新增通过系统分享菜单直接上传到 WebDAV（NAS）的能力。
>
> A fork of [linesoft2/open2share](https://github.com/linesoft2/open2share) that keeps the original open-to-share conversion and adds direct uploads to a WebDAV server (NAS) from the Android share sheet.
>
> 包名 / applicationId：`top.linesoft.share2znas`（可与原版并存）

## WebDAV 上传 / WebDAV upload

设置 → **WebDAV 上传**，可配置：

| 项 | 说明 |
| --- | --- |
| 协议 | `http` / `https` |
| 服务器地址 | 域名或 IP，也可直接粘贴完整地址（`https://nas.example.com:5006/dav`） |
| 端口 | 留空则使用 443 / 80 |
| 基础路径 | 服务器上的 WebDAV 根目录（可选） |
| 用户名 / 密码 | HTTP Basic 认证 |
| 上传目录 | 每行一个，第一行为默认目录 |
| 上传前选择目录 | 多个目录时弹窗选择 |
| 自动创建目录 | 缺失的目录用 MKCOL 逐级创建 |
| 同名文件直接覆盖 | 关闭时自动重命名为 `name_时间戳.ext` |
| 信任所有证书 | 用于自签名证书的局域网 NAS（会关闭证书校验） |

配置好后，任意应用的分享菜单里都会出现“上传到 NAS (WebDAV)”：

- 单文件、多文件（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）均支持；
- 纯文本分享会保存为 `clip_yyyyMMdd_HHmm.txt`；
- 上传在前台服务中进行，通知栏显示进度，完成/失败会弹通知（失败通知点击可直接进入设置）。

实现参考了 [HTTP-Shortcuts](https://github.com/Waboodoo/HTTP-Shortcuts) 的文件上传方式：用 OkHttp 直接从内容 URI 流式 PUT，配合 HTTP Basic 认证。


**这是一款可以将打开文件(open, ACTION_VIEW)转换为分享文件(share, ACTION_SEND)的Android小工具。**

**An Android app that can convert open(ACTION_VIEW) files to share(ACTION_SEND) files.**

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/top.linesoft.open2share/)

## 使用方法/Usage

在打开文件时选择“转换为分享文件”，然后再进行分享操作即可。

Select "Convert to share" when opening the file, and then share it.
