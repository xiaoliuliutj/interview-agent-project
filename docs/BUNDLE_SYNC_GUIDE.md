# 通过 Git Bundle 同步项目到虚拟机

当虚拟机无法访问 GitHub 时，可以在开发机生成 Git Bundle，再复制到虚拟机导入。

## 一、开发机生成 Bundle

在项目根目录执行（PowerShell）：

```powershell
cd D:\实习\项目\interviewGuide

$bundlePath = "_scratch\interview-agent-main-$(git rev-parse --short HEAD).bundle"
if (Test-Path -LiteralPath $bundlePath) {
  Remove-Item -LiteralPath $bundlePath -Force
}

git bundle create $bundlePath main
git bundle verify $bundlePath
git bundle list-heads $bundlePath
```

Bundle 只包含已提交的 Git 内容，不包含未提交修改、`.env` 或其他被 `.gitignore` 忽略的文件。

## 二、复制 Bundle 到虚拟机

将 `<VM_IP>` 和 `<VM_USER>` 替换为实际值：

```powershell
scp "D:\实习\项目\interviewGuide\_scratch\interview-agent-main-<COMMIT>.bundle" `
  <VM_USER>@<VM_IP>:~/interview-agent.bundle
```

## 三、虚拟机导入 Bundle

先确认虚拟机工作区没有未提交修改：

```bash
cd ~/interviewGuide
git status
```

导入 Bundle，并创建临时分支引用：

```bash
git fetch ~/interview-agent.bundle main:bundle-update
git log --oneline main..bundle-update
```

确认提交内容后，使用快进方式更新当前 `main`：

```bash
git merge --ff-only bundle-update
git branch -d bundle-update
```

`--ff-only` 不会自动创建复杂合并，也不会覆盖本地提交。如果无法快进，停止操作并检查本地分支差异。

## 四、重新构建 Docker 服务

本次 Java 依赖修改只需要重建 Java 后端：

```bash
cd ~/interviewGuide/infrastructure
docker compose build --no-cache java-backend
docker compose up -d java-backend
```

如果同时同步了前端修改，则执行：

```bash
docker compose up -d --build frontend
```

查看服务状态：

```bash
docker compose ps
docker compose logs --tail=100 java-backend
```

## 五、验证简历上传

浏览器执行 `Ctrl + F5`，重新上传 PDF。若仍失败，优先查看 `java-backend` 日志中的第一个 `Caused by`。

## 注意事项

- 不要使用 `git reset --hard` 来解决同步问题。
- 不要把 `.env`、API Key 或数据库密码放入 Bundle。
- Bundle 文件只用于传输，不应提交到 Git 仓库。
- 如果虚拟机存在未提交修改，先提交或备份后再导入 Bundle。
