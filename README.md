# file-server

Spring Boot 4.1.0 + Gradle + Java 25 的文件上传/下载/删除服务，提供 REST API 与一个静态列表页。
文件元数据使用 H2 数据库管理，二进制文件存放在 `upload/` 目录。

## 运行

```bash
./gradlew bootRun
```

或打包后直接运行：

```bash
./gradlew bootJar
java -jar build/libs/file-server-0.0.1-SNAPSHOT.jar
```

服务启动后会在进程工作目录下自动创建 `upload/` 和 H2 数据文件 `data/file-server.mv.db`。

启动完成后控制台会打印可访问的地址：

```
----------------------------------------------------------------
  应用已启动，可通过以下地址访问：
    本机访问   http://localhost:8080/
    本机访问   http://127.0.0.1:8080/
    局域网访问 http://192.168.0.18:8080/
    H2 控制台  http://localhost:8080/h2-console
----------------------------------------------------------------
```

* 端口取实际监听端口，因此 `server.port=0`（随机端口）也能打印出正确地址。
* 设置了 `server.address` 时只绑定该网卡，此时只打印该地址；设置了 `server.servlet.context-path` 时地址会带上该前缀。
* 局域网地址取自各网卡的 site-local IPv4（`10.x` / `172.16-31.x` / `192.168.x`）；
  虚拟网卡（VPN、容器网桥等）仅在没有物理网卡地址时作为兜底，避免打印出无法访问的地址。
* 该输出由 `AccessUrlLogger` 在 `ApplicationReadyEvent` 时打印。

允许上传的文件类型共 23 种，其他类型返回 400：

| 分类 | 后缀 |
| --- | --- |
| Office | `.doc` `.docx` `.xls` `.xlsx` `.ppt` `.pptx` |
| 文档 | `.pdf` `.ofd` `.txt` |
| 图像 | `.jpg` `.jpeg` `.png` `.gif` `.bmp` `.webp` `.tif` `.tiff` `.avif` `.heic` |
| 相机 RAW | `.arw` `.nef` `.dng` `.rw2` |

白名单在 `FileStorageService.ALLOWED_EXTENSIONS`，页面脚本顶部的 `ALLOWED_EXTENSIONS` 与文件选择框的
`accept` 是它的镜像，三处需一起改（改动后可用 README 末尾的一致性脚本自检）。

## 存储结构

```
data/file-server.mv.db                   H2 数据库（文件元数据）
upload/<md5>.<ext>                       在库文件，以「二进制 MD5 + 原扩展名」命名
upload/deleted/<yyyy-MM-dd>/<name>       已删除文件，按删除日期分目录存放
```

* 每个上传文件在 `file_record` 表中保存**原始文件名**（`original_name`）与**存储路径**（`stored_path`），
  下载时通过 `Content-Disposition` 还原原始文件名（中文名同样支持）。
* 删除是软删除：二进制文件被移动到 `upload/deleted/<删除日期>/`（同名冲突时自动追加时间戳后缀），
  数据库行保留并置 `deleted=true`，同时记录 `deleted_at` 与 `deleted_path`。
* 相同内容重复上传会复用同一份存储文件，并刷新对应的在库记录（响应中 `duplicate=true`）。
* 启动时会扫描 `upload/`，把数据库中没有在库记录的既有文件自动登记为一条记录（原始名取存储名），
  因此升级前上传的旧文件仍会出现在列表中。

## 数据库

H2 以文件模式运行，连接信息在 `src/main/resources/application.properties`：

```
spring.datasource.url=jdbc:h2:file:./data/file-server;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
```

`AUTO_SERVER=TRUE` 允许同一份数据库文件被多个进程同时打开（例如 IDE 里跑一个、命令行再跑一个），
否则第二个进程会以 `Database may be already in use` 启动失败。

启动后可通过 <http://localhost:8080/h2-console> 查看数据（JDBC URL 填上面的地址，用户名 `sa`，密码留空）。
注意 Spring Boot 4 把 H2 控制台拆成了独立模块，需要 `spring-boot-h2console` 依赖才会生效。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/files` | multipart 上传，**一次请求可传多个文件**，字段名 `fileUpload`（兼容 `file`） |
| GET | `/api/files` | 列出在库文件，按上传时间倒序 |
| GET | `/api/files/{name}` | 按存储名下载，响应头带原始文件名 |
| DELETE | `/api/files/{id}` | 删除单个文件 |
| DELETE | `/api/files?ids=1,2,3` | 批量删除，失败项互不影响 |

列表项字段：`id`、`name`（存储名）、`originalName`（原始文件名）、`storedPath`（存储路径）、
`size`、`contentType`、`md5`、`uploadedAt`、`downloadUrl`。

上传响应：每个 part 独立处理，一个文件被拒不会影响同请求里的其他文件；
`results` 与请求顺序一一对应，`index` 即 part 下标。

```json
{
  "requested": 3,
  "uploaded": 2,
  "failed": 1,
  "results": [
    { "index": 0, "name": "发票.ofd", "success": true,
      "file": { "id": 1, "name": "5c313abc....ofd", "originalName": "发票.ofd",
                "storedPath": "upload/5c313abc....ofd", "size": 12,
                "duplicate": false, "downloadUrl": "/api/files/5c313abc....ofd" },
      "reason": null },
    { "index": 1, "name": "病毒.exe", "success": false, "file": null,
      "reason": "unsupported file type, allowed: ..." }
  ]
}
```

删除响应：

```json
{
  "requested": 2,
  "deleted": 2,
  "files": [
    { "id": 21, "originalName": "季度报告.pdf",
      "storedPath": "upload/5609c2f3....pdf",
      "archivedPath": "upload/deleted/2026-09-10/5609c2f3....pdf" }
  ],
  "failed": []
}
```

上传接口会把 URL 查询参数和 multipart 表单中的非文件字段打印到控制台。

CORS 已全局开启，允许任意来源、方法和请求头。

## 文档签名 webhook 测试接收端

`POST /api/webhooks/document-sign` 用于验签并记录平台回调，不需要 URL 查询参数，成功接收或重复投递均返回 **204，无响应体**。默认关闭；在仓库根目录初始化本地证书和签名秘密，再显式加载 HTTPS 测试配置：

```bash
./scripts/setup-document-sign-webhook.sh
./gradlew bootRun --args='--spring.config.additional-location=file:./config/document-sign-webhook.properties'
# 或
java -jar build/libs/file-server-0.0.1-SNAPSHOT.jar \
  --spring.config.additional-location=file:./config/document-sign-webhook.properties
```

HTTPS 测试配置监听 `0.0.0.0:8443`，三个接收地址为：

* `https://192.168.0.18:8443/api/webhooks/document-sign`
* `https://127.0.0.1:8443/api/webhooks/document-sign`
* `https://localhost:8443/api/webhooks/document-sign`

配置入口是 `config/document-sign-webhook.properties`。实际签名秘密及密钥库密码保存在 `.local/webhook/application.properties`，发送方需使用其中 `webhook.document-sign.signing-secret` 的原始字符串作为 UTF-8 密钥。该目录被 Git 忽略，秘密和私钥不应提交或输出到日志。初始化脚本不覆盖已有材料；应用开启 webhook 但未配置签名秘密时启动失败。普通启动仍默认关闭该路由。

自签名证书位于 `.local/webhook/receiver.crt`，PKCS12 密钥库为 `.local/webhook/receiver.p12`。证书 SAN 包含上述三个主机名/IP；调用端需显式信任该证书，例如 `curl --cacert .local/webhook/receiver.crt https://localhost:8443/`，不使用 `-k`。脚本不会修改操作系统的信任库。路径均相对仓库根目录，启动命令也应在此目录执行。

请求必须为 JSON，包含 `eventId`、`jobId`、`operation`、`resultAvailable`（布尔值）、`state`、`timestamp`，并携带以下三个非空请求头：

* `X-Document-Sign-Event-Id`：必须与 JSON 中的 `eventId` 一致。
* `X-Document-Sign-Timestamp`：本次投递时间，ISO-8601 Instant；保持原字符串参与签名，允许与 body 的事件时间不同。
* `X-Document-Sign-Signature`：`v1=` 加 64 位小写十六进制 HMAC-SHA256。

签名输入依次为四个 frame：固定域 `rhc-office.document-sign.webhook.v1` 的 UTF-8、事件 ID 头的 ASCII、投递时间头的 ASCII、原始 body 字节。每段先写四字节大端字节长度，再写内容。接收端使用常量时间比较，并校验投递时间位于当前时间正负五分钟内；每次重试也需重新验签。`eventId` 和 `jobId` 必须为 UUID，`state` 必须为 `SUCCEEDED`、`FAILED`、`CANCELLED` 或 `QUARANTINED`，body 的 `timestamp` 为 ISO-8601 Instant。

接收端将**原始请求字节**、三个请求头和接收时间存入现有 H2 数据库的独立表，新事件标记为 `VERIFIED`；提交成功后才确认接收。以 `eventId` 去重，重复请求不新增事件；同一 ID 的请求体字节不同返回 409，保留首次记录。请求体最多 1 MiB，超限返回 413；非法请求返回 400，签名或投递时间校验失败返回 401，存储失败返回 503，供平台重试。未开启时路由返回 404。历史 `UNVERIFIED` 记录不会被改写为已验签。

不新增公开查询接口，HTTPS 测试配置关闭 H2 Web 控制台。数据库文件仍为 `data/file-server.mv.db`，表名为 `document_sign_webhook_event`，可在本地数据库工具中执行：

```sql
SELECT event_id, received_at, verification_status
FROM document_sign_webhook_event ORDER BY received_at DESC;
```

`raw_body` 为未经重新序列化的 BLOB，`signature_timestamp`、`signature` 保留首次投递的请求头；事件 ID 请求头与 `event_id` 相同。请将这些记录作为测试数据管理，其中包含完整回调内容。

接入文档明确要求正式 worker 的目标为 HTTPS 域名，禁止裸 IP 或解析到不允许地址的域名。上述 IP/localhost 与自签名证书用于本地联调，**不代表已满足正式发送平台的 DNS/IP 准入或真实投递验收**。正式接入仍需已准入的域名、网络及发送方信任的证书，不关闭证书或目标校验。若配置了 context-path，接收地址需加此前缀。

## 页面

打开 <http://localhost:8080/> 查看文件列表：

### 上传（多文件 + 拖拽）

* 顶部是一个 **drop 区域**：把文件直接拖进去即可上传，也可以点击区域（或按 Enter/空格）打开文件选择框，
  选择框带 `multiple`，可一次选多个文件。
* 拖拽 `dragenter`/`dragleave` 用计数器处理（子元素会反复触发这两个事件），
  悬停时区域变蓝；拖到区域外不会让浏览器跳转到该文件（全局拦截了默认行为）。
* 拖入文件夹时提示「暂不支持文件夹」并忽略，不影响同批的其他文件。
* 上传队列逐条显示**原始文件名 / 大小 / 状态**（等待中、上传中、成功、失败原因），顶部汇总成功与失败数量，
  可「清空列表」；每次新的上传会重置队列。
* **分批请求**：队列按 `BATCH_BYTE_LIMIT`（默认 100MB）切成若干批，每批一个 multipart 请求、
  最多 `MAX_CONCURRENT_REQUESTS`（默认 2）个请求并行；单个超过 100MB 的文件会独占一个请求，
  这样一次拖入很多大文件也不会撑爆 `max-request-size`。
* 后缀不在白名单里的文件在**客户端就直接标红**（不浪费请求），服务端仍然会再校验一次。

### 列表（分组 tab + 多选删除）

* 列表按**文件后缀名格式分组**显示，每组是一个 **Chrome 风格的 tab**，tab 与内容区同属一个圆角整体：

  | 分组（tab） | 后缀 |
  | --- | --- |
  | OFD 文档 | `ofd` |
  | PDF 文档 | `pdf` |
  | 图像文档 | `jpg` `jpeg` `png` `gif` `bmp` `webp` `tif` `tiff` `avif` `heic` |
  | RAW 图像 | `arw` `nef` `dng` `rw2` |
  | Office 文档 | `doc` `docx` `xls` `xlsx` `ppt` `pptx` |
  | Text 文本文档 | `txt` `text` `csv` `tsv` `md` `log` `json` `xml` `yml` `yaml` `html` `htm` |
  | 其他文档 | 以上都匹配不到的后缀 |

  分组规则写在页面脚本顶部的 `FILE_GROUPS` 常量里，改数组即可调整 tab 顺序和类型；
  只有真的存在文件的组才会渲染出 tab，所以新增分组在没有对应文件时不会多出一个空 tab。
* tab 外观：激活的 tab 用白色并与下方内容区无缝相接，两侧用径向渐变画出 Chrome 那种内凹圆角过渡；
  整块外壳统一 10px 圆角、`overflow:hidden` 收边。tab 上的数字是「本组选中数/本组总数」，
  有选中时变蓝。支持左右方向键切换 tab。
* **多选**：每行复选框、表头复选框（选中当前 tab 全部，部分选中显示半选）、工具栏「全选」（选中所有分组）；
* 工具栏显示「已选择 N / M 个文件」，可「删除选中」「取消选择」「刷新」，跨 tab 选择同样支持批量删除；
* 删除前会二次确认，选中多个文件时一次性全部删除；某组被删空后对应 tab 自动消失并切到下一个 tab；
* 每行可「拷贝地址」「下载」「删除」。

## 示例

```bash
# 上传单个文件（额外查询参数和表单字段会打印到控制台）
curl -F "fileUpload=@/path/to/report.pdf;filename=季度报告.pdf" -F "remark=hello" \
  "http://localhost:8080/api/files?userId=100"

# 一次上传多个文件：重复同一个字段名即可
curl -F "fileUpload=@发票.ofd" -F "fileUpload=@合同.pdf" -F "fileUpload=@照片.png" \
  http://localhost:8080/api/files

# 列表
curl http://localhost:8080/api/files

# 批量删除
curl -X DELETE "http://localhost:8080/api/files?ids=21,22"
```

## 上传限制

* `spring.servlet.multipart.max-file-size=200MB` —— 单个文件上限。
* `spring.servlet.multipart.max-request-size=500MB` —— 单次请求上限，因为一个请求可能带多个文件，
  必须比 `max-file-size` 大；页面按 100MB 一批拆分，所以正常情况下不会触到这个值。
* 两者都在 `src/main/resources/application.properties` 中调整。

## 改白名单后的自检

白名单镜像在三处（后端常量、页面常量、文件选择框 `accept`），改完跑一下这段确认没有漏改：

```bash
python3 - <<'PY'
import re
java = open('src/main/java/com/example/fileserver/FileStorageService.java', encoding='utf-8').read()
html = open('src/main/resources/static/index.html', encoding='utf-8').read()

backend = set(re.findall(r'"([a-z0-9]+)"',
    re.search(r'ALLOWED_EXTENSIONS = Set\.of\((.*?)\);', java, re.S).group(1)))
frontend = set(re.findall(r"'([a-z0-9]+)'",
    re.search(r'const ALLOWED_EXTENSIONS = \[(.*?)\];', html, re.S).group(1)))
accept = {a.lstrip('.') for a in re.search(r'accept="([^"]+)"', html).group(1).split(',')}
grouped = set().union(*[set(re.findall(r"'([a-z0-9]+)'", g))
    for g in re.findall(r'extensions: \[(.*?)\]', html)])

print('backend  =', len(backend))
print('frontend =', len(frontend), 'match:', backend == frontend)
print('accept   =', len(accept),   'match:', backend == accept)
print('未归入任何具名分组(会落到「其他文档」):', sorted(backend - grouped) or '无')
PY
```
