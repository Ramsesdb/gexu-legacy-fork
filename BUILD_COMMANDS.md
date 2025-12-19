# 🔧 Build Commands - Gexu

Guía rápida de comandos Gradle para compilar y probar Gexu.

---

## 🚀 APK para Pruebas en Teléfono (Óptimo)

### ⭐ Preview Build (Óptimo para Testing de Rendimiento)

```bash
# Build optimizado con firma debug (RECOMENDADO para probar en teléfono)
./gradlew :app:installStandardPreview

# APK generado en:
# app/build/outputs/apk/standard/preview/app-standard-arm64-v8a-preview.apk
```

> [!TIP]
> **Preview** es un Release con firma debug: código optimizado con R8, sin logs excesivos, APK más pequeño (~40-60MB), y **máximo rendimiento**. Perfecto para testing real sin configurar keystore.

---

### Debug Build (Para desarrollo con logs)

```bash
# Build rápido sin optimización (para debugging)
./gradlew :app:assembleStandardDebug

# APK generado en:
# app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk
```

### Instalar Directamente en el Dispositivo

```bash
# Preview (recomendado para rendimiento)
./gradlew :app:installStandardPreview

# Debug (para desarrollo con logs)
./gradlew :app:installStandardDebug

# O manualmente con ADB:
adb install -r app/build/outputs/apk/standard/preview/app-standard-arm64-v8a-preview.apk
```

> [!NOTE]
> El build `debug` compila más rápido pero **NO** aplica minificación (R8). El APK será más grande (~100-120MB) y el rendimiento será menor.

---

## 📦 Todos los Build Types

| Comando | Descripción | Uso |
|---------|-------------|-----|
| `assembleStandardDebug` | Debug con logs y depuración | Desarrollo diario |
| `assembleStandardRelease` | Release optimizado con R8 | Distribución |
| `assembleStandardPreview` | Release con firma debug | Testing pre-release |
| `assembleStandardFoss` | Sin servicios propietarios | F-Droid compatible |
| `assembleStandardBenchmark` | Optimizado para profiling | Medición de rendimiento |

```bash
# Ejemplos:
./gradlew :app:assembleStandardRelease
./gradlew :app:assembleStandardPreview
./gradlew :app:assembleStandardFoss
```

---

## 🧹 Comandos de Limpieza

```bash
# Limpiar todo el proyecto
./gradlew clean

# Limpiar y reconstruir
./gradlew clean :app:assembleStandardDebug
```

---

## ✅ Verificación de Código (CI)

```bash
# Verificar formato de código (spotless/ktlint)
./gradlew spotlessCheck

# Aplicar formato automáticamente
./gradlew spotlessApply

# Ejecutar tests unitarios
./gradlew testStandardDebugUnitTest
```

---

## 📲 Comandos ADB Útiles

```bash
# Listar dispositivos conectados
adb devices

# Instalar APK (reemplazar si existe)
adb install -r <path_to_apk>

# Desinstalar app
adb uninstall com.ramsesbr.gexu.debug

# Ver logs en tiempo real (filtrar por app)
adb logcat | findstr "gexu"

# Capturar logs completos
adb logcat -d > logcat.txt
```

---

## 🎯 Comandos Combinados (Copy-Paste Ready)

### Build + Install rápido
```powershell
./gradlew :app:installStandardDebug
```

### Limpieza completa + Build
```powershell
./gradlew clean :app:assembleStandardDebug
```

### Verificar formato + Build
```powershell
./gradlew spotlessApply :app:assembleStandardDebug
```

### Build Release (para distribuir)
```powershell
./gradlew :app:assembleStandardRelease
```

---

## 📁 Ubicación de APKs Generados

| Build Type | Ruta del APK |
|------------|--------------|
| Debug | `app/build/outputs/apk/standard/debug/` |
| Release | `app/build/outputs/apk/standard/release/` |
| Preview | `app/build/outputs/apk/standard/preview/` |
| FOSS | `app/build/outputs/apk/standard/foss/` |

> [!NOTE]
> El proyecto genera APKs por arquitectura (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) y uno universal. Para teléfonos modernos, usa `arm64-v8a`.

---

## 📋 Requisitos

- **JDK**: 17+
- **Android SDK**: API 26-35
- **NDK**: Incluido en el proyecto
- **Espacio en disco**: ~5GB para builds completos
