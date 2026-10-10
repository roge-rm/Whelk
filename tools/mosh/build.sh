#!/bin/sh
# Builds mosh-client for one Android ABI, with static ncurses, Nettle and
# Protobuf, and writes it as OUT/ABI/libmosh-client.so. Android only lets an
# app run programs from its native library folder, hence the name.
#
#   tools/mosh/build.sh ABI NDK OUT
#
# Sources are downloaded once into build/native/src and checked against the
# hashes below. Work happens in build/native/ABI. Runs from the app's Gradle
# build when the output is missing; on its own it should go through heavy.
set -eu

ABI=$1
NDK=$2
OUT=$(mkdir -p "$3" && cd "$3" && pwd)
API=27
JOBS=${MOSH_JOBS:-5}

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC=$ROOT/build/native/src
WORK=$ROOT/build/native/$ABI
PREFIX=$WORK/prefix

MOSH=mosh-1.4.0
NCURSES=ncurses-6.5
NETTLE=nettle-3.10.1
PROTOBUF=protobuf-3.21.12

case $ABI in
    arm64-v8a) TARGET=aarch64-linux-android ;;
    armeabi-v7a) TARGET=armv7a-linux-androideabi ;;
    x86_64) TARGET=x86_64-linux-android ;;
    x86) TARGET=i686-linux-android ;;
    *) echo "Unknown ABI $ABI" >&2; exit 1 ;;
esac
HOST=$TARGET
[ "$ABI" = armeabi-v7a ] && HOST=arm-linux-androideabi

TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
export CC="$TC/$TARGET$API-clang"
export CXX="$TC/$TARGET$API-clang++"
export AR=$TC/llvm-ar
export RANLIB=$TC/llvm-ranlib
export STRIP=$TC/llvm-strip
export CFLAGS="-O2 -fPIC"
export CXXFLAGS="-O2 -fPIC"

fetch() {
    file=$1 url=$2 sum=$3
    mkdir -p "$SRC"
    if [ ! -f "$SRC/$file" ]; then
        curl -sSfL -o "$SRC/$file.part" "$url"
        mv "$SRC/$file.part" "$SRC/$file"
    fi
    echo "$sum  $SRC/$file" | sha256sum -c --quiet -
}

fetch $MOSH.tar.gz https://github.com/mobile-shell/mosh/releases/download/mosh-1.4.0/$MOSH.tar.gz \
    872e4b134e5df29c8933dff12350785054d2fd2839b5ae6b5587b14db1465ddd
fetch $NCURSES.tar.gz https://invisible-island.net/archives/ncurses/$NCURSES.tar.gz \
    136d91bc269a9a5785e5f9e980bc76ab57428f604ce3e5a5a90cebc767971cc6
fetch $NETTLE.tar.gz https://ftp.gnu.org/gnu/nettle/$NETTLE.tar.gz \
    b0fcdd7fc0cdea6e80dcf1dd85ba794af0d5b4a57e26397eee3bc193272d9132
fetch protobuf-cpp-3.21.12.tar.gz https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protobuf-cpp-3.21.12.tar.gz \
    4eab9b524aa5913c6fffb20b2a8abf5ef7f95a80bc0701f3a6dbb4c607f73460
fetch protoc-21.12-linux-x86_64.zip https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protoc-21.12-linux-x86_64.zip \
    3a4c1e5f2516c639d3079b1586e703fc7bcfa2136d58bda24d1d54f949c315e8

# The protoc that turns Mosh's .proto files into C++ runs on this machine.
PROTOC=$ROOT/build/native/protoc/bin/protoc
if [ ! -x "$PROTOC" ]; then
    mkdir -p "$ROOT/build/native/protoc"
    (cd "$ROOT/build/native/protoc" && unzip -qo "$SRC/protoc-21.12-linux-x86_64.zip")
fi

# Each stage leaves a stamp, so a run after a failure starts where it stopped.
mkdir -p "$WORK" "$PREFIX"
cd "$WORK"
done_() { [ -f "$WORK/.$1" ]; }
stamp() { touch "$WORK/.$1"; }
for t in $MOSH $NCURSES $NETTLE; do [ -d $t ] || tar xzf "$SRC/$t.tar.gz"; done
[ -d $PROTOBUF ] || tar xzf "$SRC/protobuf-cpp-3.21.12.tar.gz"

# ncurses: only the terminal descriptions, with the common ones built in so
# no terminfo files are needed on the phone.
done_ ncurses || (
    cd $NCURSES
    ./configure --host=$HOST --prefix="$PREFIX" \
        --without-shared --with-normal --without-debug --without-cxx --without-cxx-binding \
        --without-ada --without-progs --without-tests --without-manpages --disable-stripping \
        --enable-overwrite --enable-widec --disable-database --disable-db-install \
        --with-fallbacks=xterm-256color,xterm,screen-256color,screen,tmux-256color,tmux,linux,vt100 \
        > configure.log
    make -j"$JOBS" libs > make.log
    make install.libs install.includes > install.log
) && stamp ncurses

# Nettle: Mosh only uses its AES.
done_ nettle || (
    cd $NETTLE
    ./configure --host=$HOST --prefix="$PREFIX" --disable-shared --enable-static \
        --disable-documentation --disable-public-key --disable-assembler --disable-openssl \
        > configure.log
    make -j"$JOBS" > make.log
    make install > install.log
) && stamp nettle

# Protobuf, the C++ library only.
done_ protobuf || {
cmake -S $PROTOBUF -B protobuf-build -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=$API -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_PROTOC_BINARIES=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF \
    -Dprotobuf_WITH_ZLIB=OFF > protobuf-cmake.log
cmake --build protobuf-build -j"$JOBS" --target libprotobuf-lite > protobuf-make.log
cp protobuf-build/libprotobuf-lite.a "$PREFIX/lib/"
(cd $PROTOBUF/src && find google -name '*.h' -o -name '*.inc' | while read -r f; do
    mkdir -p "$PREFIX/include/$(dirname "$f")"
    cp "$f" "$PREFIX/include/$f"
done)
stamp protobuf
}

# Mosh, the client only, with the C++ library linked in.
(
    cd $MOSH
    ./configure --host=$HOST --prefix="$PREFIX" --disable-server --enable-client \
        --with-crypto-library=nettle --disable-hardening \
        PROTOC="$PROTOC" PKG_CONFIG=/bin/false \
        CPPFLAGS="-I$PREFIX/include" \
        LDFLAGS="-L$PREFIX/lib -static-libstdc++ -pie" \
        protobuf_CFLAGS="-I$PREFIX/include" protobuf_LIBS="-lprotobuf-lite -llog" \
        Nettle_CFLAGS="-I$PREFIX/include" Nettle_LIBS="-lnettle" \
        TINFO_LIBS="-lncursesw" \
        CXXFLAGS="$CXXFLAGS -std=c++17" \
        > configure.log
    make -j"$JOBS" > make.log
)

mkdir -p "$OUT/$ABI"
$STRIP -o "$OUT/$ABI/libmosh-client.so" $MOSH/src/frontend/mosh-client
echo "Built $OUT/$ABI/libmosh-client.so"
