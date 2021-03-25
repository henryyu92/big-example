### MOB 对象存储

### HBase 社区流程

- 创建 isuue 描述相关背景：地址为 https://issues.apache.org/jira/browse/HBASE  初次使用需要发邮件给 dev@hbase.apache.org，开通 Jira 的 Contributor 权限
- 将 issue assign 给自己
- 将代码修改导出 patch，并 attach 到 issue 上，代码提交到本地 git 仓库后，通过 git format -l 将最近一次提交的代码导出到 patch 文件中，并将文件命名为 `<issue-id>.<branch>.v1.patch`，然后可以在 github 提交 pull request
- 将 issue 状态更改为 path available
- HadoopQA 自动为新提交的 Patch 跑所有 UT
- 待 UT 通过后，请求 Commiter/PMC 代码 review
- 完成代码修改，并获得 Commiter/PMC 的 +1 认可
- 注意编写 issue 的 Release Note 内容，告诉未来使用新版本 HBase 的用户，本次修改主要对用户有何影响
- commiter/PMC 将代码提交到官方 git
- 将 issue 状态设为 Resolved