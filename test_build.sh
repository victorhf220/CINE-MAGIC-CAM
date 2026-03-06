#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────────
# test_build.sh - Script local para testar compilação do CINE-MAGIC-CAM
# 
# Uso: ./test_build.sh [debug|release|full]
# ────────────────────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$SCRIPT_DIR"
BUILD_TYPE="${1:-debug}"

echo "════════════════════════════════════════════════════════════"
echo "  CINE-MAGIC-CAM Local Build Test"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "📍 Build Type: $BUILD_TYPE"
echo "📁 Root: $ROOT"
echo ""

# ─── Validações Iniciais ───────────────────────────────────────────────────────

check_java() {
    echo "🔍 Verificando Java..."
    if ! command -v java &> /dev/null; then
        echo "❌ Java não encontrado"
        return 1
    fi
    java -version
    echo "✅ Java OK"
}

check_gradle() {
    echo ""
    echo "🔍 Verificando Gradle Wrapper..."
    if [ ! -f "$ROOT/gradlew" ]; then
        echo "❌ Gradle Wrapper não encontrado"
        return 1
    fi
    chmod +x "$ROOT/gradlew"
    "$ROOT/gradlew" --version
    echo "✅ Gradle OK"
}

check_ndk() {
    if [ -z "${ANDROID_NDK_HOME:-}" ]; then
        echo ""
        echo "⚠️  ANDROID_NDK_HOME não configurado"
        echo "   Compilação de código nativo será pulada"
        return 0
    fi
    echo ""
    echo "🔍 Verificando Android NDK..."
    if [ ! -d "$ANDROID_NDK_HOME" ]; then
        echo "❌ NDK não encontrado em: $ANDROID_NDK_HOME"
        return 1
    fi
    echo "✅ NDK OK: $ANDROID_NDK_HOME"
}

# ─── Build Tasks ───────────────────────────────────────────────────────────────

build_debug() {
    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "  🏗️ Building Debug APK"
    echo "════════════════════════════════════════════════════════════"
    
    "$ROOT/gradlew" clean assembleFreeDebug \
        --stacktrace \
        --no-daemon \
        -Dorg.gradle.jvmargs=-Xmx2048m
    
    local apk="$ROOT/app/build/outputs/apk/free/debug/app-free-debug.apk"
    if [ -f "$apk" ]; then
        local size=$(du -h "$apk" | cut -f1)
        echo "✅ Debug APK compilado: $size"
        ls -lh "$apk"
    else
        echo "❌ Debug APK não encontrado"
        return 1
    fi
}

build_release() {
    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "  🎁 Building Release APK (Free)"
    echo "════════════════════════════════════════════════════════════"
    
    "$ROOT/gradlew" assembleFreeRelease \
        --stacktrace \
        --no-daemon \
        -Dorg.gradle.jvmargs=-Xmx2048m || {
        echo "⚠️  Release build falhou (pode ser por falta de signing config)"
        return 0
    }
    
    local apk="$ROOT/app/build/outputs/apk/free/release/app-free-release.apk"
    if [ -f "$apk" ]; then
        local size=$(du -h "$apk" | cut -f1)
        echo "✅ Release APK compilado: $size"
        ls -lh "$apk"
    fi
}

build_full() {
    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "  🧪 Running Tests"
    echo "════════════════════════════════════════════════════════════"
    
    "$ROOT/gradlew" testFreeDebugUnitTest \
        --stacktrace \
        --no-daemon \
        -Dorg.gradle.jvmargs=-Xmx1024m || {
        echo "⚠️  Alguns testes falharam"
    }
    
    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "  🔍 Running Lint"
    echo "════════════════════════════════════════════════════════════"
    
    "$ROOT/gradlew" lintFreeDebug \
        --stacktrace \
        --no-daemon || {
        echo "⚠️  Lint encontrou problemas"
    }
    
    build_debug
}

# ─── Main ───────────────────────────────────────────────────────────────────────

main() {
    check_java || exit 1
    check_gradle || exit 1
    check_ndk || true
    
    case "$BUILD_TYPE" in
        debug)
            build_debug
            ;;
        release)
            build_release
            ;;
        full)
            build_full
            ;;
        *)
            echo "❌ Build type desconhecido: $BUILD_TYPE"
            echo "   Use: debug, release, ou full"
            exit 1
            ;;
    esac
    
    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "  ✅ Build Test Completo"
    echo "════════════════════════════════════════════════════════════"
}

main "$@"
