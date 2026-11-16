# Issue tracker: GitHub

本项目的任务和规格使用 GitHub Issues 管理，仓库为 `BurnerK/iWork`。
使用 `gh` CLI 创建、读取、列出、评论、标记和关闭任务。
创建任务前确认目标仓库；仅向用户自己的仓库提交任务。

任务之间优先使用 GitHub 原生子任务和依赖关系；
不可用时，在任务正文中分别记录 `Part of #编号` 和 `Blocked by: #编号`。
所有阻塞任务关闭后，该任务才可开始。

发布到任务跟踪器时创建 GitHub Issue；获取任务时读取对应 Issue。

## Pull requests as a triage surface

**PRs as a request surface: no.**

## Wayfinding

使用 `wayfinder:map` 标签标识地图 Issue；子任务关联到地图，
并使用 `wayfinder:<type>` 标签。认领任务时分配给当前用户，
完成后记录结果、关闭任务，并更新地图中的决策线索。
