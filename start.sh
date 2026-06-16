#!/bin/bash

echo "======================================"
echo "  Retro Shooter - 一键启动脚本"
echo "======================================"

if ! command -v docker &> /dev/null; then
    echo "❌ 错误: 未检测到 Docker，请先安装 Docker"
    exit 1
fi

if ! command -v docker-compose &> /dev/null; then
    echo "❌ 错误: 未检测到 Docker Compose，请先安装 Docker Compose"
    exit 1
fi

echo ""
echo "📋 检查环境变量配置..."
if [ ! -f .env ]; then
    echo "   .env 文件不存在，从 .env.example 复制..."
    cp .env.example .env
fi

echo ""
echo "🚀 启动所有服务..."
echo ""
docker-compose up -d --build

echo ""
echo "⏳ 等待服务启动..."
sleep 10

echo ""
echo "✅ 服务启动完成！"
echo ""
echo "📱 访问地址："
echo "   游戏页面:    http://localhost/game/play/"
echo "   排行榜:      http://localhost/leaderboard/"
echo "   回放查看:    http://localhost/replay/"
echo "   后端API:     http://localhost:8080/api/health"
echo "   归档服务:    http://localhost:8081/health"
echo ""
echo "📝 查看日志:    docker-compose logs -f"
echo "⏹️  停止服务:    docker-compose down"
echo ""
echo "🎮 祝您游戏愉快！"
