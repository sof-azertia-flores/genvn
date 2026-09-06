#!/usr/bin/env bash
# package-backend.sh -- bundle everything needed to run the genvn backend on another machine.
#
#   backend/tools/package-backend.sh [options]
#
# The bundle is a .tar.gz containing the runnable jar, the config template, a launcher for
# sh and for Windows, the local image stand-in, a deployment note and a manifest with
# checksums. By default nothing private is included: your live config/application.yml
# (API keys) and your data/ directory (saves, pictures) stay out unless you ask for them.
#
# Options
#   --out DIR       where to write the archive        (default: backend/dist)
#   --name NAME     archive base name                 (default: genvn-backend-<version>-<stamp>)
#   --skip-build    reuse build/libs/genvn-backend-*.jar instead of running ./gradlew bootJar
#   --with-config   include config/application.yml   (contains your API keys -- handle with care)
#   --with-data     include data/                    (saves and generated pictures)
#   --source        also include the source tree, so the bundle can be rebuilt and tested
#   -h, --help      this text
set -euo pipefail

BACKEND="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$BACKEND/.." && pwd)"
OUT="$BACKEND/dist"
NAME=""
SKIP_BUILD=0
WITH_CONFIG=0
WITH_DATA=0
WITH_SOURCE=0

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; }
say()   { printf '%s\n' "$*"; }
die()   { printf 'package-backend: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --out)         [ $# -ge 2 ] || die "--out needs a directory"; OUT="$2"; shift 2 ;;
        --name)        [ $# -ge 2 ] || die "--name needs a value";    NAME="$2"; shift 2 ;;
        --skip-build)  SKIP_BUILD=1; shift ;;
        --with-config) WITH_CONFIG=1; shift ;;
        --with-data)   WITH_DATA=1; shift ;;
        --source)      WITH_SOURCE=1; shift ;;
        -h|--help)     usage; exit 0 ;;
        *)             die "unknown option '$1' (try --help)" ;;
    esac
done

command -v java >/dev/null 2>&1 || die "java is not on PATH; the backend needs Java 21"
command -v tar  >/dev/null 2>&1 || die "tar is required"
if command -v sha256sum >/dev/null 2>&1; then SHA="sha256sum"; else SHA="shasum -a 256"; fi

# ------------------------------------------------------------------ build
cd "$BACKEND"
if [ "$SKIP_BUILD" -eq 0 ]; then
    say "==> Building the runnable jar (./gradlew bootJar)"
    ./gradlew bootJar -q
fi
JAR="$(ls -t build/libs/genvn-backend-*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -n 1 || true)"
[ -n "$JAR" ] && [ -f "$JAR" ] || die "no build/libs/genvn-backend-*.jar found; run without --skip-build"
VERSION="$(basename "$JAR" .jar | sed 's/^genvn-backend-//')"
[ -n "$VERSION" ] || VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts | head -n 1)"
STAMP="$(date +%Y%m%d-%H%M)"
[ -n "$NAME" ] || NAME="genvn-backend-${VERSION}-${STAMP}"

# The jar must never carry a live config: the packaged application.properties only holds
# loopback defaults, and the real file is read from ./config at startup.
if unzip -l "$JAR" 2>/dev/null | grep -q 'application\.yml'; then
    die "the jar contains an application.yml; refusing to package it"
fi

# ------------------------------------------------------------------ stage
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/genvn-package.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
BUNDLE="$STAGE/$NAME"
mkdir -p "$BUNDLE/config" "$BUNDLE/tools" "$BUNDLE/data"

cp "$JAR" "$BUNDLE/genvn-backend.jar"
cp config/application.example.yml "$BUNDLE/config/application.example.yml"
cp tools/image_stub.py "$BUNDLE/tools/image_stub.py"
printf '%s\n' "$VERSION" > "$BUNDLE/VERSION"

if [ "$WITH_CONFIG" -eq 1 ]; then
    [ -f config/application.yml ] || die "--with-config given but config/application.yml does not exist"
    cp config/application.yml "$BUNDLE/config/application.yml"
    chmod 600 "$BUNDLE/config/application.yml"
    say "!!  config/application.yml is included. It holds your API keys: keep the archive private."
fi

if [ "$WITH_DATA" -eq 1 ]; then
    if [ -d data ]; then
        # Saves and pictures, minus any half-written temp files from an interrupted save.
        (cd data && tar -cf - --exclude='*.tmp' --exclude='.DS_Store' .) | (cd "$BUNDLE/data" && tar -xf -)
        say "==> data/ included ($(du -sh data | cut -f1) on disk)"
    else
        say "==> --with-data given but there is no data/ directory yet; skipping"
    fi
fi

if [ "$WITH_SOURCE" -eq 1 ]; then
    say "==> Including the source tree"
    SRC="$BUNDLE/source"
    mkdir -p "$SRC/backend/config" "$SRC/backend/tools"
    (cd "$BACKEND" && tar -cf - \
        --exclude='.DS_Store' \
        src build.gradle.kts settings.gradle.kts gradlew gradlew.bat gradle .gitignore) \
        | (cd "$SRC/backend" && tar -xf -)
    cp config/application.example.yml "$SRC/backend/config/"
    cp tools/*.py tools/*.sh "$SRC/backend/tools/" 2>/dev/null || true
    for doc in README.md IMPLEMENTATION_NOTES.md NEXT_WINDOW.md run-backend.sh; do
        [ -f "$ROOT/$doc" ] && cp "$ROOT/$doc" "$SRC/$doc"
    done
fi

# ------------------------------------------------------------------ launchers
cat > "$BUNDLE/run.sh" <<'RUN'
#!/usr/bin/env sh
# Starts the genvn backend from this directory. Settings live in config/application.yml;
# the first start copies the commented template there if the file does not exist yet.
set -e
cd "$(dirname "$0")"
[ -f config/application.yml ] || cp config/application.example.yml config/application.yml
exec java -jar genvn-backend.jar "$@"
RUN
chmod +x "$BUNDLE/run.sh"

cat > "$BUNDLE/run.bat" <<'RUN'
@echo off
rem Starts the genvn backend from this directory. Settings live in config\application.yml.
cd /d "%~dp0"
if not exist config\application.yml copy config\application.example.yml config\application.yml >nul
java -jar genvn-backend.jar %*
RUN

# ------------------------------------------------------------------ deployment note
cat > "$BUNDLE/DEPLOY.md" <<DOC
# genvn backend ${VERSION} -- 部署说明

打包时间：$(date '+%Y-%m-%d %H:%M')

## 需要什么

- Java 21 或更新（\`java -version\` 应显示 21+）。
  Java 21~24 请用 **JDK** 包，不要用 JRE 包：Temurin 的 JRE 不含 \`jdk.random\` 模块，
  骰子会自动退回 \`SecureRandom\`，并在日志里留一行告警（能正常玩，只是少了 JDK 自带的生成器）。
  Java 25 起这些生成器已并入 \`java.base\`，两种包都可以，\`--list-modules\` 里查不到
  \`jdk.random\` 是正常的。
- 一个能访问文字模型接口的网络；不填 api-key 则以离线 mock 模式运行。
- 前端另行部署（Vite 开发服务器或 \`frontend/dist\` 静态文件），通过 \`/api\` 访问本服务。

## 启动

\`\`\`bash
./run.sh          # Linux / macOS
run.bat           # Windows
\`\`\`

第一次启动会把 \`config/application.example.yml\` 复制成 \`config/application.yml\`。
在那里填入 \`llm.api-key\`、\`llm.base-url\`、\`llm.model\`，需要图片时再填 \`image.*\`。
改完配置需要重启。所有设置都在这个文件里，不使用环境变量。

## 目录

- \`genvn-backend.jar\`  可运行的后端，Spring Boot 单文件。
- \`config/application.example.yml\`  带注释的配置模板；\`config/application.yml\` 是实际生效的文件。
- \`data/sessions/\`  存档，每局一个 JSON；\`data/assets/<存档id>/\`  图片与清单。
- \`tools/image_stub.py\`  本地图片接口替身，用来在没有图片 API 的机器上演示图片流程。

## 迁移已有存档

把原机器的 \`backend/data/\` 整个目录复制到这里的 \`data/\`（或打包时加 \`--with-data\`）。
存档与图片清单是可移植的，路径不写死。

## 更新

替换 \`genvn-backend.jar\` 后重启即可；\`config/\` 与 \`data/\` 不动。

## 从局域网或公网访问

默认只监听 127.0.0.1，且没有密钥：任何能连到端口的人都能玩、都会花你的模型额度。
对外提供服务前，在 \`config/application.yml\` 里：

\`\`\`yaml
genvn:
  access-key: "一串足够长的随机字符串"        # 浏览器第一次打开时要求输入，之后每次请求都带上
  allowed-origins: ["https://vn.example.com"] # 前端所在的来源（不同域名部署时填；本机来源始终允许）
\`\`\`

推荐保持 127.0.0.1，前面放一个带 HTTPS 的反向代理（nginx / Caddy）把 \`/api\` 转发到
127.0.0.1:8080，并把读超时放大到 10 分钟（一次选择可能等模型好几分钟）。把
\`server.address\` 改成 0.0.0.0 直接暴露端口只适合可信网络：密钥在纯 HTTP 上是明文传输的。
后端域名不要经过会在 100 秒左右切断空闲连接的 CDN/WAF 代理，否则长生成会 504。

前端用 \`frontend/tools/package-frontend.sh --api https://你的后端域名\` 构建后，把
\`frontend/dist/\` 的内容上传到任意静态托管 / CDN 即可。

## 校验

见 \`MANIFEST.txt\` 中每个文件的 SHA-256。
DOC

# ------------------------------------------------------------------ manifest
find "$BUNDLE" -name '.DS_Store' -delete
(
    cd "$BUNDLE"
    say "genvn backend $VERSION -- packaged $(date '+%Y-%m-%d %H:%M')"
    say "options: with-config=$WITH_CONFIG with-data=$WITH_DATA source=$WITH_SOURCE"
    say ""
    find . -type f ! -name MANIFEST.txt | sort | while read -r f; do
        printf '%-72s %10s  %s\n' "${f#./}" "$(wc -c < "$f" | tr -d ' ')" "$($SHA "$f" | cut -d' ' -f1)"
    done
) > "$BUNDLE/MANIFEST.txt"

# Belt and braces: no private file may slip in without the flag that names it.
if [ "$WITH_CONFIG" -eq 0 ] && [ -e "$BUNDLE/config/application.yml" ]; then
    die "internal error: live config staged without --with-config"
fi

# ------------------------------------------------------------------ archive
mkdir -p "$OUT"
ARCHIVE="$OUT/$NAME.tar.gz"
# COPYFILE_DISABLE keeps macOS from adding ._* resource-fork entries to the archive.
(cd "$STAGE" && COPYFILE_DISABLE=1 tar -czf "$ARCHIVE" "$NAME")

say ""
say "==> $ARCHIVE"
say "    size    $(du -h "$ARCHIVE" | cut -f1 | tr -d ' ')"
say "    sha256  $($SHA "$ARCHIVE" | cut -d' ' -f1)"
say "    files   $(tar -tzf "$ARCHIVE" | grep -vc '/$')"
say ""
say "Unpack and start:  tar -xzf $(basename "$ARCHIVE") && cd $NAME && ./run.sh"
