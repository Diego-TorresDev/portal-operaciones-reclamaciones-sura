
package com.sura.portal_reclamaciones.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.sura.portal_reclamaciones.entity.AuditLog;
import com.sura.portal_reclamaciones.entity.ParameterChangeLog;
import com.sura.portal_reclamaciones.entity.ParameterConfig;
import com.sura.portal_reclamaciones.entity.Usuario;
import com.sura.portal_reclamaciones.repository.AuditLogRepository;
import com.sura.portal_reclamaciones.repository.ParameterChangeLogRepository;
import com.sura.portal_reclamaciones.repository.ParameterConfigRepository;
import com.sura.portal_reclamaciones.repository.UsuarioRepository;

/**
 * Administra los parámetros de negocio guardados en PostgreSQL.
 *
 * La idea es que podamos modificar la configuración desde el portal
 * sin cambiar el código cada vez que se ajuste una regla de negocio.
 * Cada actualización conserva su historial para poder revisar quién
 * hizo el cambio y cuáles eran los valores anterior y nuevo.
 */
@Service
public class ParametroService {

    private final ParameterConfigRepository parametroRepository;
    private final ParameterChangeLogRepository historialRepository;
    private final AuditLogRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final ObjectMapper objectMapper;

    public ParametroService(
            ParameterConfigRepository parametroRepository,
            ParameterChangeLogRepository historialRepository,
            AuditLogRepository auditoriaRepository,
            UsuarioRepository usuarioRepository,
            ObjectMapper objectMapper) {

        this.parametroRepository = parametroRepository;
        this.historialRepository = historialRepository;
        this.auditoriaRepository = auditoriaRepository;
        this.usuarioRepository = usuarioRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Devuelve todos los parámetros ordenados por clave.
     *
     * Entregamos una vista sencilla en lugar de exponer directamente
     * la entidad JPA y sus detalles internos.
     */
    @Transactional(readOnly = true)
    public List<ParametroVista> listar() {

        return parametroRepository.findAllByOrderByParameterKeyAsc()
                .stream()
                .map(this::convertirVista)
                .toList();
    }

    /**
     * Actualiza un parámetro y registra el cambio dentro de una
     * única transacción. Si falla alguna escritura, la transacción
     * evita que quede el parámetro actualizado sin su historial.
     */
    @Transactional
    public ParametroVista actualizar(
            String claveIngresada,
            String nuevoValorIngresado,
            String motivoIngresado,
            String usernameIngresado) {

        String clave = normalizarClave(claveIngresada);
        String username = normalizarUsername(usernameIngresado);

        if (clave.isBlank() || clave.length() > 120) {
            throw new IllegalArgumentException(
                    "La clave del parámetro no es válida."
            );
        }

        if (nuevoValorIngresado == null) {
            throw new IllegalArgumentException(
                    "El nuevo valor del parámetro es obligatorio."
            );
        }

        String nuevoValor = nuevoValorIngresado.trim();
        String motivo = motivoIngresado == null
                ? ""
                : motivoIngresado.trim();

        // Pedimos una explicación para que el historial sea útil en una auditoría.
        if (motivo.length() < 10 || motivo.length() > 500) {
            throw new IllegalArgumentException(
                    "El motivo debe tener entre 10 y 500 caracteres."
            );
        }

        ParameterConfig parametro = parametroRepository.findById(clave)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No existe el parámetro solicitado."
                ));

        /*
         * La identidad se obtiene de la cuenta existente en la base de datos.
         * El controlador deberá entregar el username extraído del JWT validado,
         * nunca un identificador enviado libremente en el cuerpo de la petición.
         */
        Usuario usuario = usuarioRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo identificar al usuario autenticado."
                ));

        // Validamos según el tipo declarado antes de persistir cualquier cambio.
        validarValor(parametro, nuevoValor);

        String valorAnterior = parametro.getParameterValue();

        // Si el valor no cambia, evitamos crear un registro de cambio innecesario.
        if (valorAnterior.equals(nuevoValor)) {
            return convertirVista(parametro);
        }

        // Actualizamos el parámetro y anotamos quién hizo el cambio.
        parametro.setParameterValue(nuevoValor);
        parametro.setUpdatedByUserId(usuario.getId());

        parametroRepository.save(parametro);

        // Guardamos el antes y el después para conservar el historial completo.
        ParameterChangeLog cambio = new ParameterChangeLog();
        cambio.setParameterKey(clave);
        cambio.setOldValue(valorAnterior);
        cambio.setNewValue(nuevoValor);
        cambio.setChangedByUserId(usuario.getId());
        cambio.setChangedByUsername(usuario.getUsername());
        cambio.setReason(motivo);

        historialRepository.save(cambio);

        // La bitácora general registra la operación sin guardar credenciales ni tokens.
        AuditLog auditoria = new AuditLog();
        auditoria.setActorUserId(usuario.getId());
        auditoria.setActorUsername(usuario.getUsername());
        auditoria.setActionCode("PARAMETRO_ACTUALIZADO");
        auditoria.setResourceType("PARAMETRO");
        auditoria.setResourceId(clave);
        auditoria.setResult("OK");
        auditoria.setMessage("Se actualizó un parámetro de negocio.");

        Map<String, Object> detalles = new HashMap<>();
        detalles.put("clave", clave);
        detalles.put("tipo", parametro.getValueType());
        detalles.put("motivo", motivo);

        auditoria.setDetails(detalles);
        auditoriaRepository.save(auditoria);

        return convertirVista(parametro);
    }

    /**
     * Comprueba que el dato corresponda al tipo definido en la tabla.
     * Un valor inválido se rechaza antes de guardar el parámetro.
     */
    private void validarValor(ParameterConfig parametro, String valor) {

        String tipo = parametro.getValueType()
                .trim()
                .toUpperCase(Locale.ROOT);

        try {
            switch (tipo) {
                case "INTEGER" -> {
                    long numero = Long.parseLong(valor);

                    if (numero < 0) {
                        throw new IllegalArgumentException(
                                "El parámetro debe ser un entero no negativo."
                        );
                    }
                }

                case "DECIMAL" -> {
                    BigDecimal numero = new BigDecimal(valor);

                    if (numero.signum() < 0) {
                        throw new IllegalArgumentException(
                                "El parámetro debe ser un decimal no negativo."
                        );
                    }
                }

                case "DATE" -> {
                    /*
                     * FECHA_REFERENCIA puede quedar vacía mientras la demo
                     * no haya fijado la fecha que se va a utilizar.
                     * Las demás fechas deben tener formato ISO: AAAA-MM-DD.
                     */
                    if (valor.isBlank()
                            && "FECHA_REFERENCIA".equals(parametro.getParameterKey())) {
                        break;
                    }

                    LocalDate.parse(valor);
                }

                case "JSON" -> {
                    // El lector JSON comprueba la estructura antes de guardar el texto.
                    if (valor.isBlank() || objectMapper.readTree(valor) == null) {
                        throw new IllegalArgumentException(
                                "El valor debe contener un JSON válido."
                        );
                    }
                }

                case "BOOLEAN" -> {
                    String booleano = valor.toUpperCase(Locale.ROOT);

                    if (!List.of("TRUE", "FALSE", "SI", "NO", "S", "N")
                            .contains(booleano)) {
                        throw new IllegalArgumentException(
                                "El valor booleano debe ser true, false, sí o no."
                        );
                    }
                }

                case "STRING" -> {
                    if (valor.isBlank()) {
                        throw new IllegalArgumentException(
                                "El parámetro no puede quedar vacío."
                        );
                    }
                }

                default -> throw new IllegalArgumentException(
                        "El tipo de parámetro no está reconocido."
                );
            }
        } catch (IllegalArgumentException error) {
            // Conservamos los mensajes de validación que ya son comprensibles.
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "El nuevo valor no cumple el formato del parámetro."
            );
        }
    }

    private String normalizarClave(String clave) {
        return clave == null
                ? ""
                : clave.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizarUsername(String username) {
        return username == null
                ? ""
                : username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Copia solo los campos que necesita la respuesta de la API.
     */
    private ParametroVista convertirVista(ParameterConfig parametro) {
        return new ParametroVista(
                parametro.getParameterKey(),
                parametro.getParameterValue(),
                parametro.getValueType(),
                parametro.getDescription(),
                parametro.getUpdatedAt(),
                parametro.getUpdatedByUserId()
        );
    }

    /**
     * Forma pública de representar un parámetro en las respuestas HTTP.
     */
    public record ParametroVista(
            String clave,
            String valor,
            String tipo,
            String descripcion,
            Instant actualizadoEn,
            Long actualizadoPorUsuarioId) {
    }
}
