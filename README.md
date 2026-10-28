# Portal de Reclamaciones - Backend

Backend REST para evaluación y seguimiento de reclamaciones. La aplicación expone autenticación JWT, evaluación de reglas, revisión humana, lotes de pago simulados, auditoría, resumen y exportación CSV.

> **Estado de verificación: 27 de octubre de 2026.** Java 17 y Spring Boot 4.1.2. `clean verify` terminó en `BUILD SUCCESS`, con 2 pruebas y 0 fallos. La base local ya tiene aplicada Flyway V1; no reimportar el Excel ni modificar la migración para repetir pruebas de API.

## Tecnologías

- Java 17, Spring Boot 4.1.2, WebFlux/Netty.
- Spring Security, JWT Bearer, autoridades `SCOPE_ADMIN` y `SCOPE_ANALISTA`.
- Spring Data JPA / Hibernate; PostgreSQL; Flyway.
- Springdoc OpenAPI 3.2.0.

## Requisitos y configuración

1. JDK 17 en `PATH`.
2. PostgreSQL en ejecución y base configurada en `src/main/resources/application.properties` (base local verificada: `sura_reclamaciones`).
3. Definir las credenciales y variables requeridas fuera del repositorio. `app.jwt.secret` recibe la configuración externa `JWT_SECRET`: clave Base64 que decodifique a por lo menos 32 bytes para HS256. No subir secretos, contraseñas ni tokens al repositorio.

## Ejecutar en Windows

Desde la raíz de `backend`:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

La prueba de contexto valida que Flyway V1 esté aplicado; la prueba unitaria simula un fallo de festivos sin caché y comprueba respuesta y auditoría. Para las pruebas normales no hace falta volver a importar el insumo.

## Swagger / OpenAPI

Con la aplicación iniciada:

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

Se verificó OpenAPI HTTP 200 sin JWT y `GET /api/casos` HTTP 401 sin JWT. Swagger está público en la configuración actual de evaluación; protegerlo o deshabilitarlo por perfil antes de producción.

## Rutas principales

| Área | Ruta | Acceso conocido |
|---|---|---|
| Login | `POST /api/auth/login` | Pública; devuelve JWT con credenciales válidas |
| Casos | `GET /api/casos`; `GET /api/casos/{identificador}` | JWT; filtros, paginación y detalle |
| Seguimiento | `GET /api/casos/{identificador}/tracking` | JWT |
| Toma de caso | `POST /api/casos/{codigoCaso}/tomar` | `SCOPE_ANALISTA` |
| Decisión | `PATCH /api/casos/{codigoCaso}/estado` | JWT y validaciones de negocio/asignación |
| Parámetros | `PUT /api/parametros/**` | `SCOPE_ADMIN` |
| Crear lote | `POST /api/lotes` | `SCOPE_ANALISTA` o `SCOPE_ADMIN` |
| Confirmar pago simulado | `PATCH /api/lotes/{id}/pagar` | `SCOPE_ADMIN` |
| Reportes | `GET /api/reportes/resumen`; `GET /api/reportes/exportar` | JWT |

## Estado actual del escenario de prueba

- Insumo cargado una sola vez: 60 casos y catálogos relacionados.
- Motor de 17 reglas: 1.020 resultados individuales para 60 casos.
- Tras ajustar `MONTO_AUTO_BASICO`: 30 casos `AUTOMATICO` y 30 `MANUAL`.
- `FECHA_REFERENCIA` permanece en `2026-10-14`, como exige el escenario.
- Se confirmó un lote de pago simulado por 31 casos: 31 casos están cerrados y 29 siguen en bandeja. No se realiza ninguna transferencia real.
- No borrar ni revertir estos estados, ni reimportar el Excel; son parte de la demostración y sus historiales.

## CSV final

Archivo validado en el equipo local: `%USERPROFILE%\Downloads\resultados_reclamaciones_prueba.csv`.

Contiene 60 filas y ocho encabezados, en orden: `ID_Caso`, `Decision`, `Reglas_Incumplidas`, `Motivo`, `Prioridad`, `Estado_Actual`, `Fecha_Limite_ANS`, `Semaforo_ANS`. Copiar ese archivo a la carpeta de entrega junto con el backend y volver a confirmar las 60 filas después de copiarlo.

## Seguridad, auditoría y límites

- JWT sin sesión de servidor; decodificador HS256 y clave externa con mínimo de 32 bytes.
- Se verificó restricción ADMIN para confirmar lotes y ANALISTA para tomar casos; la lista enmascara documentos y el detalle autenticado presenta datos según su caso de uso.
- Se observaron registros de auditoría/seguimiento en cambios de parámetros, revisión y lotes. La prueba de CSV no detectó valores de fórmula peligrosos sin neutralizar.
- No se verificaron tiempo de expiración/renovación/revocación JWT, rate limiting, TLS, CORS, rotación de clave, respaldos ni monitoreo. Deben revisarse antes de producción.
- El pago es simulado. Swagger/OpenAPI se deja público solo para facilitar esta evaluación; deshabilitarlo o restringirlo en producción.

## Documentos

- `docs/ARQUITECTURA_TECNICA.pdf` y `.docx`
- `docs/INFORME_SEGURIDAD.pdf` y `.docx`
- CSV de 60 casos (copiarlo desde Descargas a la entrega).
