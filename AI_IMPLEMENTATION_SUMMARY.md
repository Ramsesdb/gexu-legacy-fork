# Resumen de Implementación de Inteligencia Artificial en Gexu

Este documento proporciona un análisis técnico profundo de la arquitectura, integración y funcionalidades de **Gexu AI**, el asistente contextual integrado en la aplicación.

## 1. Arquitectura del Sistema

El sistema de IA está diseñado siguiendo los principios de **Clean Architecture**, asegurando que la lógica de negocio, la persistencia de datos y la interfaz de usuario estén desacopladas.

### 1.1 Capa de Datos (`Data Layer`)
- **Implementación**: `AiRepositoryImpl.kt`
- **Proveedores Soportados**:
  - **Gemini (Google)**: Implementación nativa mediante llamadas REST optimizadas.
  - **OpenAI (GPT)**: Cliente compatible con la API estándar de Chat.
  - **Anthropic (Claude)**: Soporte para la API de claude-3.
  - **OpenRouter**: Acceso a múltiples modelos a través de una API unificada.
  - **Custom**: Permite configurar `Base URL` para servidores locales (Ollama, LocalAI) o proxies.
- **Protocolo**: Todas las comunicaciones son **Stateless**. El historial se mantiene en memoria durante la sesión y se envía completo para mantener el contexto.
- **Seguridad**: Las claves de API se almacenan en `AiPreferences`, validadas localmente y nunca compartidas.

### 1.2 Motor de Contexto (`GetReadingContext.kt`)
El "cerebro" detrás de la personalización. El sistema construye un **System Prompt** dinámico:

1.  **Guardia Anti-Spoilers**:
    - Algoritmo: Consulta `historyRepository` para encontrar el capítulo máximo leído.
    - Instrucción: `CRITICAL INSTRUCTION: Do NOT spoil anything beyond chapter $maxChapterRead.`
    
2.  **Perfilado del Usuario (Global Context)**:
    - Analiza "Top Genres" de la biblioteca del usuario.
    - Lista series completadas y en lectura para dar recomendaciones personalizadas.
    - **Personalidad (`AiTone`)**: El usuario puede configurar el tono (Amigable, Formal, Entusiasta, Conciso) para adaptar la respuesta.

3.  **Contexto Local**:
    - Inyecta Título, Autor, Sinopsis y posición de lectura actual.

### 1.3 Capa de Presentación (`UI Layer`)
La interfaz es una **Superposición Composable (Overlay)** inyectada directamente en el ciclo de vida del Lector.

- **Componente**: `AiChatOverlay.kt`
- **Diseño**: "Gexu AI" totalmente integrado con Branding consistente.
- **Interacción**:
  - **Overlay**: Fondo semitransparente que captura toques.
  - **Teclado**: Gestión avanzada con `imePadding()` y `adjustNothing` para evitar saltos de layout en el lector.

---

## 2. Configuración Avanzada y Gestión (`Settings`)

Se ha implementado un sistema robusto de configuración ubicado en **Settings -> Gexu AI**.

### 2.1 Pantalla de Configuración (`SettingsGexuAiScreen.kt`)
Utiliza el patrón `SearchableSettings` del proyecto para integración nativa.

- **Gestión de Proveedores**:
  - Interfaz unificada para seleccionar proveedor, ingresar API Key y seleccionar Modelo específico.
  - Validación en tiempo real de API Keys.
  
- **Personalidad y Comportamiento**:
  - **Tono (`AiTone`)**: Enum configurable (Friendly, Formal, Casual, Enthusiastic, Concise).
  - **Temperatura**: Slider (0.0 - 1.0) para controlar la creatividad vs precisión.
  - **Instrucciones Custom**: Campo de texto libre para añadir directivas extra al System Prompt.
  - **Toggles**: Activar/Desactivar Anti-Spoiler, Contexto de Lectura e Historial.

---

## 3. Soluciones Técnicas a Desafíos Críticos

### 3.1 Conflicto de Input (Teclado vs Lector)
**El Problema**: Eventos de teclado (espacio, enter) causaban navegación de página en el lector.

**La Solución (`ReaderActivity.kt`)**:
- Flag global `isAiChatOpen`.
- **Interceptación**: `dispatchKeyEvent` bloquea eventos al lector si el chat está activo.
- **Manifest**: `android:windowSoftInputMode="adjustNothing"` evita redimensionado brusco del Canvas de lectura.

### 3.2 Gestión de Hilos y Red
- Ejecución segura en `Dispatchers.IO` dentro de `viewModelScope`.
- Manejo de `SocketTimeoutException` y `RateLimiting` (429) con feedback visual al usuario.

---

## 4. Estructura de Archivos Clave

### Dominio & Lógica
*   `domain/src/main/java/tachiyomi/domain/ai/GetReadingContext.kt`: Generador de Prompts.
*   `domain/src/main/java/tachiyomi/domain/ai/AiTone.kt`: Enum de personalidades.
*   `domain/src/main/java/tachiyomi/domain/ai/AiPreferences.kt`: Preferencias (Keys, Tono, Modelos).

### Datos e Infraestructura
*   `data/src/main/java/tachiyomi/data/ai/AiRepositoryImpl.kt`: Cliente HTTP Multi-provider.

### Interfaz de Usuario
*   `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsGexuAiScreen.kt`: Pantalla de configuración completa.
*   `app/src/main/java/eu/kanade/presentation/ai/components/AiChatOverlay.kt`: UI del Chat.
*   `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt`: Entrada en menú principal.

---

## 5. Estado Actual

### Funcionalidades Completadas
- [x] **Rebranding Completo**: "AI Chat" -> "Gexu AI" en toda la app.
- [x] **Configuración Centralizada**: Nueva pantalla en Settings.
- [x] **Personalización**: Tono, Temperatura e Instrucciones Custom.
- [x] **Gestión de Proveedores**: UI amigable para configurar Keys y Modelos.
- [x] **Protección de Contexto**: Anti-Spoiler basado en historial de lectura.
- [x] **RAG Local**: Indexado vectorial de la biblioteca para consultas offline.
    - Implementación de `VectorStore` con búsquedas paraleas.
    - Soporte híbrido: Embeddings Gemini (768-dim) + Local (MediaPipe 100-dim).

### Próximos Pasos
- [ ] **Visión**: Soporte para modelos multimodales (enviar imagen de página actual).
