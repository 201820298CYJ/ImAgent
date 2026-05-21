# 基于官方 ES 8.x 镜像，预装 IK 中文分词器
FROM elasticsearch:8.17.0

# 安装 IK 分词器插件（版本必须与 ES 版本一致）
RUN elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/8.17.0

# 如果上述址下载慢，可替换为 GitHub 地址：
# RUN elasticsearch-plugin install --batch https://github.com/infinilabs/analysis-ik/releases/download/v8.17.0/elasticsearch-analysis-ik-8.17.0.zip