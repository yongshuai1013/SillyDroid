#!/system/bin/sh
set -eu

ROOTFS_DIR="${ROOTFS_DIR:?ROOTFS_DIR is required}"
LOGS_DIR="${LOGS_DIR:?LOGS_DIR is required}"

PROOT_BIN="${HOST_PROOT_BIN:?HOST_PROOT_BIN is required}"
PROOT_LIB_DIR="${HOST_PROOT_LIB_DIR:?HOST_PROOT_LIB_DIR is required}"
PROOT_LOADER_PATH="${HOST_PROOT_LOADER:?HOST_PROOT_LOADER is required}"
PROOT_LOADER_32_PATH="${HOST_PROOT_LOADER_32:-}"
LINUX_FS_DIR="$ROOTFS_DIR/fs"
PROOT_TMP_DIR="${HOST_TMP_DIR:?HOST_TMP_DIR is required}"
GUEST_PATH="/usr/sbin:/usr/bin:/sbin:/bin"

assert_file() {
	if [ ! -f "$1" ]; then
		echo "$2" >&2
		exit 1
	fi
}

assert_dir() {
	if [ ! -d "$1" ]; then
		echo "$2" >&2
		exit 1
	fi
}

assert_guest_file() {
	if [ ! -e "$LINUX_FS_DIR$1" ]; then
		echo "$2" >&2
		exit 1
	fi
}

run_in_rootfs() {
	HOME=/tmp PATH="$GUEST_PATH" "$PROOT_BIN" -r "$LINUX_FS_DIR" \
		-b /dev \
		-b /proc \
		-b /sys \
		-b "$PROOT_TMP_DIR:/tmp" \
		-w / \
		"$@"
}

assert_file "$PROOT_BIN" "缺少 proot：$PROOT_BIN"
assert_dir "$PROOT_LIB_DIR" "缺少 host proot 依赖目录：$PROOT_LIB_DIR"
assert_file "$PROOT_LOADER_PATH" "缺少 host proot loader：$PROOT_LOADER_PATH"
assert_dir "$LINUX_FS_DIR" "缺少 Linux rootfs：$LINUX_FS_DIR"

mkdir -p "$LOGS_DIR" "$PROOT_TMP_DIR"
chmod 1777 "$PROOT_TMP_DIR"

if [ -d "$PROOT_LIB_DIR" ]; then
	export LD_LIBRARY_PATH="$PROOT_LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

export PROOT_LOADER="$PROOT_LOADER_PATH"
if [ -n "$PROOT_LOADER_32_PATH" ]; then
	assert_file "$PROOT_LOADER_32_PATH" "缺少 host proot loader32：$PROOT_LOADER_32_PATH"
	export PROOT_LOADER_32="$PROOT_LOADER_32_PATH"
fi

export PROOT_TMP_DIR

assert_guest_file "/bin/sh" "缺少 guest shell：/bin/sh"
assert_guest_file "/etc/ssl/certs/ca-certificates.crt" "缺少 CA 证书 bundle：/etc/ssl/certs/ca-certificates.crt。"

run_in_rootfs /bin/sh -c 'echo runtime-exec-ok' >/dev/null
echo "Linux runtime already preloaded."