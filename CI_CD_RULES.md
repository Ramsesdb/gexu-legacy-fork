# Reglas de CI/CD del Proyecto

Este documento describe las reglas de CI/CD (Integración Continua / Despliegue Continuo) configuradas en el proyecto Gexu.

## Flujos de Trabajo (Workflows)

El proyecto utiliza **GitHub Actions** para la automatización. Los archivos de configuración se encuentran en `.github/workflows`.

### 1. Build & Test (`.github/workflows/build.yml`)
Este flujo se ejecuta **en cada Pull Request** y en commits a la rama `main`.
Garantiza la calidad del código antes de integrar cambios.

**Pasos Principales:**
1.  **Check code format (`spotlessCheck`)**:
    *   Verifica que el código cumpla con las reglas de formato estrictas.
    *   Si este paso falla, el CI falla.
2.  **Build app**:
    *   Compila la versión `Release` de la aplicación.
3.  **Run unit tests**:
    *   Ejecuta las pruebas unitarias.

### 2. Android Debug Build (`.github/workflows/android.yml`)
Este flujo se ejecuta **en cada push** a cualquier rama.
Sirve para verificar que el código compila correctamente en modo Debug.

**Pasos Principales:**
1.  **Build Debug APK**: Compila la variante `StandardDebug`.
2.  **Upload Debug APK**: Sube el APK generado como artefacto del build.

## Estándares de Código (Linting)

El proyecto utiliza **Spotless** configurado con **Ktlint**.
La configuración se define en `buildSrc/src/main/kotlin/mihon.code.lint.gradle.kts`.

### Reglas Críticas (Kotlin):

El cumplimiento de estas reglas es **OBLIGATORIO** para que pase el CI.

#### 1. Wildcard Imports (Importaciones con comodín)
*   **REGLA:** **PROHIBIDO** usar `import package.*`.
*   **Acción:** Debes importar cada clase explícitamente.
*   **Ejemplo Incorrecto:** `import androidx.compose.material3.*`
*   **Ejemplo Correcto:**
    ```kotlin
    import androidx.compose.material3.Text
    import androidx.compose.material3.Button
    ```
*   **Nota:** Esto aplica incluso si importas muchas clases del mismo paquete. El linter es estricto.

#### 2. Longitud Máxima de Línea
*   **REGLA:** Ninguna línea puede exceder los **120 caracteres**.
*   **Acción:** Debes romper líneas largas en múltiples líneas.
*   **Casos Comunes:**
    *   Cadenas largas en `logcat` o excepciones.
    *   Condiciones complejas en `if`.
    *   Definiciones de funciones con muchos parámetros.
*   **Supresión (Último Recurso):** Si una línea no se puede romper (ej. una URL larga o import necesario), usa:
    ```kotlin
    /* ktlint-disable standard:max-line-length */
    codigo_largo...
    /* ktlint-enable standard:max-line-length */
    ```

#### 3. Orden de Importaciones
*   **REGLA:** Las importaciones deben seguir el orden estándar lexicográfico.
*   **Acción:** `spotlessApply` corrige esto automáticamente, pero intenta agruparlas lógicamente al escribir.
*   **Nota:** Si Spotless falla en importaciones, ejecuta `./gradlew spotlessApply` antes de intentar arreglarlo manualmente.

#### 4. Formato General
*   **Indentación:** 4 espacios.
*   **Espacios:** Elimina espacios al final de las líneas.
*   **Final de Archivo:** Debe haber una línea vacía al final.
*   **Condiciones Complejas (`if`):**
    *   Si rompes una condición en múltiples líneas, el paréntesis de cierre `)` y la llave de apertura `{` deben estar en una nueva línea, alineados con el `if`.
    *   **Ejemplo Correcto:**
        ```kotlin
        if (
            condition1 &&
                condition2
        ) {
            // ...
        }
        ```

### XML (`.xml`)
*   Elimina espacios al final de las líneas.
*   Asegura una línea nueva al final del archivo.

## Comandos Útiles

Para verificar y corregir el formato localmente antes de enviar cambios:

```bash
# Verificar si el código cumple las reglas (sin modificar nada)
./gradlew spotlessCheck

# Aplicar correcciones automáticas de formato
# (Nota: No arregla line length ni wildcard imports automáticamente en todos los casos)
./gradlew spotlessApply
```

---

## 🤖 AI Agent Prompt

>**ROLE:** You are an expert Android developer who strictly follows Kotlin and Spotless style guidelines.
>
> **CODING RULES (CRITICAL):**
> 1.  **NO WILDCARD IMPORTS:** Never generate `import foo.bar.*`. Always explicitly list every import, no matter how long the list is.
>     -   *Verify:* Check your generated imports and expand any `*`.
> 2.  **MAX LINE LENGTH 120:** Check the length of every line you write. If a line approaches 120 characters, proactively break it.
>     -   *Strategy:* Put function parameters on separate lines, break long concatenated strings, and split complex logical conditions.
> 3.  **NAMING CONVENTIONS:** Follow strict Kotlin naming conventions.
>     -   *Variables/Fields:* MUST be `camelCase` (e.g., `val anthropicMessage`). **NEVER** usage PascalCase for variables (e.g., `val AnthropicMessage` is forbidden).
>     -   *why:* `spotlessCheck` will fail with `standard:property-naming`.
> 4.  **KDOC:** If you modify public classes or complex functions, update or add basic KDoc documentation.
> 5.  **SMART SUPPRESSION:** Only if it is impossible to comply with the length rule (e.g., fixed URLs), wrap the code with `/* ktlint-disable standard:max-line-length */` and `/* ktlint-enable ... */`. Do not abuse this.
> 6.  **INDENTATION & FORMATTING:** For multi-line `if` conditions, ensure the closing parenthesis `)` is on a new line, indented to match the `if` keyword.
>     -   *Tip:* If you are unsure about import ordering or spacing, run `./gradlew spotlessApply` (if available) or ask the user to run it.
>
> **BEFORE SUBMITTING CODE:**
> -   Ask yourself: "Will this pass `spotlessCheck`?"
> -   **Check Naming:** Did I accidentally name a variable with a capital letter?
> -   If you have edited multiple files, ensure your edits do not break the import structure.

---
