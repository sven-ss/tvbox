#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "正在检查远程 GitHub 仓库 git@github.com:sven-ss/tvbox.git ..."
if git push -u origin main; then
  echo ""
  echo "========================================="
  echo " 提交成功！GitHub Actions 已触发自动打包！"
  echo " 查看构建进度与下载 APK："
  echo " https://github.com/sven-ss/tvbox/actions"
  echo "========================================="
else
  echo ""
  echo "请先在浏览器打开 https://github.com/ZxxXinI/tvbox 点击右上角 Fork，然后再运行本脚本！"
fi
