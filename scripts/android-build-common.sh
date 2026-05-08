#!/usr/bin/env bash

if [[ -n "${STAI_ANDROID_BUILD_COMMON_LOADED:-}" ]]; then
    return 0 2>/dev/null || exit 0
fi
readonly STAI_ANDROID_BUILD_COMMON_LOADED=1

: "${STAI_ANDROID_API_LEVEL:=36}"
: "${STAI_ANDROID_BUILD_TOOLS_VERSION:=36.0.0}"
: "${STAI_ANDROID_CMDLINE_TOOLS_URL:=https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip}"
: "${STAI_ANDROID_NDK_VERSION:=android-ndk-r27}"
: "${STAI_ANDROID_NDK_LINUX_URL:=https://dl.google.com/android/repository/android-ndk-r27-linux.zip}"
: "${STAI_DOTNET_CHANNEL:=10.0}"
: "${STAI_DOTNET_INSTALL_SCRIPT_URL:=https://dot.net/v1/dotnet-install.sh}"
: "${STAI_NODE_VERSION:=25.2.1}"
: "${STAI_ROOTFS_VERSION:=1.0.0}"
: "${STAI_DOWNLOAD_PARALLELISM:=4}"

if [[ -n "${workspace_root:-}" && -d "$workspace_root/scripts" ]]; then
    readonly STAI_ANDROID_BUILD_SCRIPTS_DIR="$workspace_root/scripts"
else
    readonly STAI_ANDROID_BUILD_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
readonly STAI_DOWNLOAD_PROGRESS_SCRIPT_PATH="$STAI_ANDROID_BUILD_SCRIPTS_DIR/download-with-progress.py"
readonly STAI_EXTRACT_PROGRESS_SCRIPT_PATH="$STAI_ANDROID_BUILD_SCRIPTS_DIR/extract-with-progress.py"

declare -ag STAI_DOWNLOAD_QUEUE=()

stai_log() {
    printf '[stai-android][%s] %s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "$*" >&2
}

stai_progress_stage() {
    local current_step="$1"
    local total_steps="$2"
    local message="$3"
    local percent='0'

    if [[ "$total_steps" =~ ^[0-9]+$ && "$total_steps" -gt 0 && "$current_step" =~ ^[0-9]+$ ]]; then
        percent="$((current_step * 100 / total_steps))"
        stai_log "[$current_step/$total_steps ${percent}%] $message"
        return
    fi

    stai_log "$message"
}

stai_warn() {
    printf '[stai-android][%s][warn] %s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "$*" >&2
}

stai_fail() {
    stai_warn "$*"
    exit 1
}

stai_assert_path_exists() {
    local path="$1"
    local message="$2"

    if [[ ! -e "$path" ]]; then
        stai_fail "$message"
    fi
}

stai_find_command() {
    command -v "$1" 2>/dev/null || true
}

stai_require_command() {
    if [[ -z "$(stai_find_command "$1")" ]]; then
        stai_fail "缺少基础命令：$1"
    fi
}

stai_prepend_path() {
    local directory="$1"

    if [[ -z "$directory" || ! -d "$directory" ]]; then
        return
    fi

    case ":$PATH:" in
        *":$directory:"*)
            ;;
        *)
            export PATH="$directory:$PATH"
            ;;
    esac
}

stai_toolchain_root() {
    printf '%s\n' "${STAI_ANDROID_TOOLCHAIN_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/stai-android-toolchain}"
}

stai_detect_linux_arch() {
    case "$(uname -m)" in
        x86_64|amd64)
            printf 'x64\n'
            ;;
        aarch64|arm64)
            printf 'aarch64\n'
            ;;
        *)
            stai_fail "当前 Linux 架构不支持 Android 打包：$(uname -m)"
            ;;
    esac
}

stai_detect_node_arch() {
    case "$(stai_detect_linux_arch)" in
        x64)
            printf 'x64\n'
            ;;
        aarch64)
            printf 'arm64\n'
            ;;
    esac
}

stai_download_progress_script() {
    printf '%s\n' "$STAI_DOWNLOAD_PROGRESS_SCRIPT_PATH"
}

stai_extract_progress_script() {
    printf '%s\n' "$STAI_EXTRACT_PROGRESS_SCRIPT_PATH"
}

stai_download_queue_reset() {
    STAI_DOWNLOAD_QUEUE=()
}

stai_queue_download_if_missing() {
    local uri="$1"
    local destination_path="$2"
    local label="${3:-$(basename "$destination_path")}"

    if [[ -f "$destination_path" ]]; then
        stai_log "复用下载缓存：$destination_path"
        return
    fi

    mkdir -p "$(dirname "$destination_path")"
    STAI_DOWNLOAD_QUEUE+=("$label"$'\t'"$uri"$'\t'"$destination_path")
}

stai_run_download_queue() {
    local manifest_path=''
    local entry=''
    local label=''
    local uri=''
    local destination_path=''
    local progress_script="$STAI_DOWNLOAD_PROGRESS_SCRIPT_PATH"

    if [[ "${#STAI_DOWNLOAD_QUEUE[@]}" -eq 0 ]]; then
        return
    fi

    stai_log "开始下载 ${#STAI_DOWNLOAD_QUEUE[@]} 个文件"

    if command -v python3 >/dev/null 2>&1 && [[ -f "$progress_script" ]]; then
        manifest_path="$(mktemp)"
        printf '%s\n' "${STAI_DOWNLOAD_QUEUE[@]}" > "$manifest_path"
        python3 "$progress_script" --manifest "$manifest_path" --jobs "$STAI_DOWNLOAD_PARALLELISM"
        rm -f "$manifest_path"
        STAI_DOWNLOAD_QUEUE=()
        return
    fi

    for entry in "${STAI_DOWNLOAD_QUEUE[@]}"; do
        IFS=$'\t' read -r label uri destination_path <<< "$entry"
        stai_log "下载依赖：$uri"
        if [[ -n "$(stai_find_command curl)" ]]; then
            curl -L --fail --progress-bar --output "$destination_path" "$uri"
            continue
        fi

        if [[ -n "$(stai_find_command wget)" ]]; then
            wget -O "$destination_path" "$uri"
            continue
        fi

        stai_fail "缺少 curl 或 wget，无法下载：$uri"
    done

    STAI_DOWNLOAD_QUEUE=()
}

stai_download_file_if_missing() {
    local uri="$1"
    local destination_path="$2"
    local label="${3:-$(basename "$destination_path")}"

    stai_download_queue_reset
    stai_queue_download_if_missing "$uri" "$destination_path" "$label"
    stai_run_download_queue
}

stai_extract_archive_with_progress() {
    local archive_path="$1"
    local destination_root="$2"
    local label="$3"
    local strip_components="${4:-0}"
    local progress_script="$STAI_EXTRACT_PROGRESS_SCRIPT_PATH"

    mkdir -p "$destination_root"

    if command -v python3 >/dev/null 2>&1 && [[ -f "$progress_script" ]]; then
        if python3 "$progress_script" \
            --archive "$archive_path" \
            --destination "$destination_root" \
            --label "$label" \
            --strip-components "$strip_components"; then
            return
        fi

        stai_log "进度解压器失败，回退到系统解压：$label"
    fi

    stai_log "开始解压：$label"
    if [[ "$archive_path" == *.zip ]]; then
        unzip -q -o "$archive_path" -d "$destination_root"
    else
        tar -xf "$archive_path" -C "$destination_root"
    fi
}

stai_is_valid_java_home() {
    local candidate="$1"
    [[ -n "$candidate" && -x "$candidate/bin/java" && -x "$candidate/bin/jar" ]]
}

stai_activate_java_home() {
    local java_home="$1"

    export JAVA_HOME="$java_home"
    stai_prepend_path "$JAVA_HOME/bin"
    stai_log "使用 JDK：$JAVA_HOME"
}

stai_find_java_home() {
    local candidate=""
    local java_bin=""

    if stai_is_valid_java_home "${JAVA_HOME:-}"; then
        printf '%s\n' "$JAVA_HOME"
        return
    fi

    java_bin="$(stai_find_command java)"
    if [[ -n "$java_bin" ]]; then
        candidate="$(dirname "$(dirname "$(readlink -f "$java_bin")")")"
        if stai_is_valid_java_home "$candidate"; then
            printf '%s\n' "$candidate"
            return
        fi
    fi

    while IFS= read -r candidate; do
        if stai_is_valid_java_home "$candidate"; then
            printf '%s\n' "$candidate"
            return
        fi
    done < <(
        find /usr/lib/jvm -mindepth 1 -maxdepth 1 -type d \( -name '*21*' -o -name '*jdk*' \) 2>/dev/null | sort
    )

    candidate="$(stai_toolchain_root)/java/current"
    if stai_is_valid_java_home "$candidate"; then
        printf '%s\n' "$candidate"
    fi
}

stai_ensure_java_home() {
    local java_home=""
    local toolchain_root="$(stai_toolchain_root)"
    local install_root="$toolchain_root/java"
    local current_root="$install_root/current"
    local archive_path="$install_root/downloads/temurin-21-$(stai_detect_linux_arch).tar.gz"
    local extract_root="$install_root/extracted"
    local extracted_dir=""
    local archive_url="https://api.adoptium.net/v3/binary/latest/21/ga/linux/$(stai_detect_linux_arch)/jdk/hotspot/normal/eclipse?project=jdk"

    java_home="$(stai_find_java_home || true)"
    if stai_is_valid_java_home "$java_home"; then
        stai_activate_java_home "$java_home"
        return
    fi

    stai_require_command tar
    stai_download_file_if_missing "$archive_url" "$archive_path"
    rm -rf "$extract_root" "$current_root"
    mkdir -p "$extract_root"
    stai_extract_archive_with_progress "$archive_path" "$extract_root" 'temurin-jdk'
    extracted_dir="$(find "$extract_root" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
    stai_assert_path_exists "$extracted_dir" "JDK 解压失败：$archive_path"
    mv "$extracted_dir" "$current_root"
    stai_activate_java_home "$current_root"
}

stai_dotnet_has_required_sdk() {
    local dotnet_bin="$1"
    "$dotnet_bin" --list-sdks 2>/dev/null | grep -Eq '^10\.'
}

stai_ensure_dotnet_sdk() {
    local dotnet_bin=""
    local toolchain_root="$(stai_toolchain_root)"
    local installer_path="$toolchain_root/downloads/dotnet-install.sh"
    local install_root="$toolchain_root/dotnet"

    dotnet_bin="$(stai_find_command dotnet)"
    if [[ -n "$dotnet_bin" ]]; then
        if stai_dotnet_has_required_sdk "$dotnet_bin"; then
            stai_log "使用 dotnet：$($dotnet_bin --version) ($dotnet_bin)"
            return
        fi
    fi

    stai_download_file_if_missing "$STAI_DOTNET_INSTALL_SCRIPT_URL" "$installer_path"
    chmod +x "$installer_path"
    bash "$installer_path" --channel "$STAI_DOTNET_CHANNEL" --install-dir "$install_root" --no-path
    stai_assert_path_exists "$install_root/dotnet" "dotnet SDK 安装失败：$install_root"
    if ! stai_dotnet_has_required_sdk "$install_root/dotnet"; then
        stai_fail "本地 dotnet SDK 不包含 10.x：$install_root"
    fi

    export DOTNET_ROOT="$install_root"
    stai_prepend_path "$DOTNET_ROOT"
    stai_log "使用本地 dotnet：$($DOTNET_ROOT/dotnet --version) ($DOTNET_ROOT)"
}

stai_node_meets_requirement() {
    local node_bin="$1"
    local major_version=""

    major_version="$($node_bin -p "process.versions.node.split('.')[0]")"
    [[ "$major_version" =~ ^[0-9]+$ && "$major_version" -ge 20 ]]
}

stai_ensure_node() {
    local node_bin=""
    local npm_bin=""
    local toolchain_root="$(stai_toolchain_root)"
    local install_root="$toolchain_root/node"
    local current_root="$install_root/current"
    local archive_path="$install_root/downloads/node-v${STAI_NODE_VERSION}-linux-$(stai_detect_node_arch).tar.xz"
    local extract_root="$install_root/extracted"
    local extracted_dir=""
    local archive_url="https://nodejs.org/dist/v${STAI_NODE_VERSION}/node-v${STAI_NODE_VERSION}-linux-$(stai_detect_node_arch).tar.xz"

    node_bin="$(stai_find_command node)"
    npm_bin="$(stai_find_command npm)"
    if [[ -n "$node_bin" && -n "$npm_bin" ]]; then
        if stai_node_meets_requirement "$node_bin"; then
            stai_log "使用 Node.js：$($node_bin --version) ($node_bin)"
            return
        fi
    fi

    stai_require_command tar
    stai_download_file_if_missing "$archive_url" "$archive_path"
    rm -rf "$extract_root" "$current_root"
    mkdir -p "$extract_root"
    stai_extract_archive_with_progress "$archive_path" "$extract_root" 'node-runtime'
    extracted_dir="$(find "$extract_root" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
    stai_assert_path_exists "$extracted_dir" "Node.js 解压失败：$archive_path"
    mv "$extracted_dir" "$current_root"
    stai_prepend_path "$current_root/bin"
    stai_log "使用本地 Node.js：$($current_root/bin/node --version) ($current_root/bin/node)"
}

stai_ensure_frontend_dist() {
    local client_root="$1"
    local force_rebuild="${2:-0}"
    local dist_index="$client_root/dist/pwa/index.html"
    local install_mode="ci"

    if [[ "$force_rebuild" != "1" && -f "$dist_index" ]]; then
        stai_log "复用前端产物：$dist_index"
        return
    fi

    stai_ensure_node
    if [[ ! -f "$client_root/package-lock.json" ]]; then
        install_mode="install"
    fi

    # 前端 dist 是 Android server payload 的一部分；缺失时这里直接补齐，避免后续 zip 阶段才报错。
    stai_log "安装前端依赖：$client_root"
    (
        cd "$client_root"
        npm "$install_mode" --no-fund
    )
    stai_log "构建前端 PWA：$client_root"
    (
        cd "$client_root"
        npm run build
    )
    stai_assert_path_exists "$dist_index" "前端构建完成但缺少产物：$dist_index"
}

stai_is_valid_linux_android_sdk_root() {
    local sdk_root="$1"
    [[ -n "$sdk_root" && -d "$sdk_root" ]]
}

stai_resolve_linux_android_sdk_root() {
    local candidate=""
    local toolchain_root="$(stai_toolchain_root)"

    for candidate in \
        "${STAI_ANDROID_SDK_ROOT:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "$HOME/Android/Sdk" \
        "/opt/android-sdk" \
        "/usr/lib/android-sdk" \
        "$toolchain_root/android-sdk"
    do
        if stai_is_valid_linux_android_sdk_root "$candidate"; then
            printf '%s\n' "$(realpath -m "$candidate")"
            return
        fi
    done

    printf '%s\n' "$(realpath -m "$toolchain_root/android-sdk")"
}

stai_has_linux_android_build_tools() {
    local sdk_root="$1"
    [[ -x "$sdk_root/build-tools/$STAI_ANDROID_BUILD_TOOLS_VERSION/aapt" ]]
}

stai_write_android_sdk_licenses() {
    local sdk_root="$1"
    local licenses_root="$sdk_root/licenses"

    mkdir -p "$licenses_root"
    # Linux 构建链保持无交互，license 直接写入本地 SDK 目录，避免每台机器重复人工确认。
    cat > "$licenses_root/android-sdk-license" <<'EOF'
24333f8a63b6825ea9c5514f83c2829b004d1fee
EOF
    cat > "$licenses_root/android-sdk-preview-license" <<'EOF'
84831b9409646a918e30573bab4c9c91346d8abd
EOF
}

stai_activate_android_sdk_root() {
    local sdk_root="$1"

    export ANDROID_SDK_ROOT="$sdk_root"
    export ANDROID_HOME="$sdk_root"
    stai_prepend_path "$sdk_root/platform-tools"
    stai_log "使用 Android SDK：$sdk_root"
}

stai_ensure_linux_android_sdk() {
    local sdk_root="$1"
    local cmdline_tools_root="$sdk_root/cmdline-tools/latest"
    local sdkmanager_path="$cmdline_tools_root/bin/sdkmanager"
    local archive_path="$sdk_root/downloads/$(basename "$STAI_ANDROID_CMDLINE_TOOLS_URL")"

    stai_ensure_java_home
    stai_require_command unzip

    if [[ ! -x "$sdkmanager_path" ]]; then
        stai_download_file_if_missing "$STAI_ANDROID_CMDLINE_TOOLS_URL" "$archive_path"
        rm -rf "$cmdline_tools_root" "$sdk_root/cmdline-tools/cmdline-tools"
        mkdir -p "$sdk_root/cmdline-tools"
        stai_extract_archive_with_progress "$archive_path" "$sdk_root/cmdline-tools" 'android-cmdline-tools'
        mv "$sdk_root/cmdline-tools/cmdline-tools" "$cmdline_tools_root"
    fi

    stai_activate_android_sdk_root "$sdk_root"
    if stai_has_linux_android_build_tools "$sdk_root"; then
        return
    fi

    stai_write_android_sdk_licenses "$sdk_root"
    stai_log "安装 Android SDK 组件：platform-tools, platforms;android-${STAI_ANDROID_API_LEVEL}, build-tools;${STAI_ANDROID_BUILD_TOOLS_VERSION}"
    "$sdkmanager_path" --sdk_root="$sdk_root" \
        "platform-tools" \
        "platforms;android-${STAI_ANDROID_API_LEVEL}" \
        "build-tools;${STAI_ANDROID_BUILD_TOOLS_VERSION}"
}

stai_write_android_local_properties() {
    local android_root="$1"
    local sdk_root="$2"

    mkdir -p "$android_root"
    printf 'sdk.dir=%s\n' "$sdk_root" > "$android_root/local.properties"
    stai_log "写入 Android local.properties：$android_root/local.properties"
}

stai_is_valid_linux_android_ndk_root() {
    local ndk_root="$1"
    [[ -n "$ndk_root" && -d "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64" ]]
}

stai_ensure_linux_android_ndk_root() {
    local sdk_root="$1"
    local toolchain_root="$(stai_toolchain_root)"
    local archive_path="$toolchain_root/downloads/$(basename "$STAI_ANDROID_NDK_LINUX_URL")"
    local ndk_root=""
    local candidate=""

    stai_require_command unzip
    for candidate in \
        "${STAI_ANDROID_NDK_ROOT:-}" \
        "${ANDROID_NDK_ROOT:-}" \
        "${ANDROID_NDK_HOME:-}" \
        "$sdk_root/ndk/$STAI_ANDROID_NDK_VERSION" \
        "$HOME/Android/Sdk/ndk/$STAI_ANDROID_NDK_VERSION" \
        "$toolchain_root/android-sdk/ndk/$STAI_ANDROID_NDK_VERSION"
    do
        if stai_is_valid_linux_android_ndk_root "$candidate"; then
            ndk_root="$(realpath "$candidate")"
            export STAI_ANDROID_NDK_ROOT="$ndk_root"
            stai_log "使用 Android NDK：$ndk_root"
            printf '%s\n' "$ndk_root"
            return
        fi
    done

    stai_download_file_if_missing "$STAI_ANDROID_NDK_LINUX_URL" "$archive_path"
    rm -rf "$sdk_root/ndk/$STAI_ANDROID_NDK_VERSION"
    mkdir -p "$sdk_root/ndk"
    stai_extract_archive_with_progress "$archive_path" "$sdk_root/ndk" 'android-ndk'
    ndk_root="$sdk_root/ndk/$STAI_ANDROID_NDK_VERSION"
    stai_assert_path_exists "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64" "Android NDK 安装失败：$ndk_root"
    export STAI_ANDROID_NDK_ROOT="$ndk_root"
    stai_log "使用本地 Android NDK：$ndk_root"
    printf '%s\n' "$ndk_root"
}