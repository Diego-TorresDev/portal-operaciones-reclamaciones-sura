
package com.sura.portal_reclamaciones.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.sura.portal_reclamaciones.entity.CaseEvaluation;
import com.sura.portal_reclamaciones.entity.CaseEvaluationRule;
import com.sura.portal_reclamaciones.entity.CaseTracking;
import com.sura.portal_reclamaciones.entity.ClaimCase;
import com.sura.portal_reclamaciones.entity.Usuario;
import com.sura.portal_reclamaciones.repository.CaseEvaluationRepository;
import com.sura.portal_reclamaciones.repository.CaseEvaluationRuleRepository;
import com.sura.portal_reclamaciones.repository.CaseTrackingRepository;
import com.sura.portal_reclamaciones.repository.ClaimCaseRepository;
import com.sura.portal_reclamaciones.repository.UsuarioRepository;

@Service
public class ConsultaCasosService {

    private final ClaimCaseRepository casos;
    private final CaseEvaluationRepository evaluaciones;
    private final CaseEvaluationRuleRepository reglas;
    private final CaseTrackingRepository seguimiento;
    private final UsuarioRepository usuarios;

    public ConsultaCasosService(
            ClaimCaseRepository casos,
            CaseEvaluationRepository evaluaciones,
            CaseEvaluationRuleRepository reglas,
            CaseTrackingRepository seguimiento,
            UsuarioRepository usuarios) {

        this.casos = casos;
        this.evaluaciones = evaluaciones;
        this.reglas = reglas;
        this.seguimiento = seguimiento;
        this.usuarios = usuarios;
    }

    /**
     * Lista casos con filtros y paginación.
     *
     * El documento se enmascara en el listado. El documento completo
     * se reserva para el endpoint de detalle.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarCasos(
            String estado,
            String decision,
            String prioridad,
            String regla,
            String semaforo,
            int pagina,
            int tamano) {

        if (pagina < 0 || tamano < 1 || tamano > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La página debe ser mayor o igual a cero y el tamaño debe estar entre 1 y 100."
            );
        }

        // Cargamos los casos y sus datos de evaluación para aplicar los filtros.
        List<ClaimCase> todos = casos.findAll();

        // Creamos un mapa local para evitar consultar al mismo usuario repetidamente.
        Map<Long, String> nombresAnalistas = usuarios.findAll()
                .stream()
                .collect(Collectors.toMap(
                        Usuario::getId,
                        Usuario::getUsername,
                        (primero, segundo) -> primero
                ));

        List<FilaCaso> filtrados = new ArrayList<>();

        for (ClaimCase caso : todos) {
            CaseEvaluation evaluacion = obtenerEvaluacionActual(caso);

            String decisionActual = evaluacion != null
                    ? evaluacion.getDecision()
                    : caso.getCurrentDecision();

            String prioridadActual = evaluacion != null
                    ? evaluacion.getPriority()
                    : caso.getCurrentPriority();

            List<String> reglasFallidas = evaluacion != null
                    ? evaluacion.getFailedRuleCodes()
                    : List.of();

            // Evaluamos cada filtro antes de agregar el caso al resultado.
            if (!coincide(estado, caso.getCurrentState())) {
                continue;
            }

            if (!coincide(decision, decisionActual)) {
                continue;
            }

            if (!coincide(prioridad, prioridadActual)) {
                continue;
            }

            if (!coincide(semaforo, caso.getAnsSemaphore())) {
                continue;
            }

            if (regla != null && !regla.isBlank()
                    && reglasFallidas.stream().noneMatch(
                            codigo -> codigo.equalsIgnoreCase(regla.trim()))) {
                continue;
            }

            String nombreAnalista = caso.getAssignedAnalystId() == null
                    ? null
                    : nombresAnalistas.get(caso.getAssignedAnalystId());

            filtrados.add(new FilaCaso(
                    caso,
                    evaluacion,
                    decisionActual,
                    prioridadActual,
                    reglasFallidas,
                    nombreAnalista
            ));
        }

        // Primero prioridad alta, después media y baja; al final la fecha límite.
        filtrados.sort(
                Comparator
                        .comparingInt(
                                (FilaCaso fila) -> ordenPrioridad(fila.prioridad())
                        )
                        .thenComparing(
                                fila -> fila.caso().getAnsDeadlineDate(),
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                fila -> fila.caso().getCaseCode(),
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                        )
        );

        long total = filtrados.size();
        long desdeLong = (long) pagina * tamano;
        int desde = (int) Math.min(desdeLong, total);
        int hasta = (int) Math.min(desdeLong + tamano, total);

        List<Map<String, Object>> contenido = filtrados
                .subList(desde, hasta)
                .stream()
                .map(this::crearFilaListado)
                .toList();

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("contenido", contenido);
        respuesta.put("pagina", pagina);
        respuesta.put("tamano", tamano);
        respuesta.put("totalElementos", total);
        respuesta.put(
                "totalPaginas",
                total == 0 ? 0 : (total + tamano - 1) / tamano
        );

        return respuesta;
    }

    /**
     * Devuelve el detalle de un caso buscando por su código o por su ID.
     * Solo esta respuesta contiene el documento completo.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerDetalle(String identificador) {

        ClaimCase caso = buscarCaso(identificador);
        CaseEvaluation evaluacion = obtenerEvaluacionActual(caso);

        Map<String, Object> respuesta = new LinkedHashMap<>();

        respuesta.put("id", caso.getId());
        respuesta.put("idCaso", caso.getCaseCode());
        respuesta.put("estadoActual", caso.getCurrentState());
        respuesta.put("decision", evaluacion != null
                ? evaluacion.getDecision()
                : caso.getCurrentDecision());
        respuesta.put("prioridad", evaluacion != null
                ? evaluacion.getPriority()
                : caso.getCurrentPriority());

        respuesta.put("afiliado", caso.getAffiliateName());
        respuesta.put("documento", caso.getDocumentNumber());

        respuesta.put("poliza", caso.getNormalizedPolicyNumber());
        respuesta.put("nitPrestador", caso.getNormalizedProviderNit());
        respuesta.put("tipoServicio", caso.getNormalizedServiceType());
        respuesta.put("codigoDiagnostico", caso.getNormalizedDiagnosisCode());

        respuesta.put("valorReclamado", caso.getClaimAmount());
        respuesta.put("fechaServicio", caso.getServiceDate());
        respuesta.put("fechaRadicacion", caso.getFilingDate());
        respuesta.put("soportes", caso.getSupportsPayload());
        respuesta.put("observaciones", caso.getObservations());

        respuesta.put("fechaLimiteAns", caso.getAnsDeadlineDate());
        respuesta.put("semaforoAns", caso.getAnsSemaphore());
        respuesta.put("alertaAns", caso.getAnsAlert());

        respuesta.put("analistaAsignado", obtenerNombreAnalista(caso));

        if (evaluacion == null) {
            respuesta.put("numeroEvaluacion", null);
            respuesta.put("motivo", null);
            respuesta.put("reglasIncumplidas", List.of());
            respuesta.put("reglas", List.of());
        } else {
            respuesta.put(
                    "numeroEvaluacion",
                    evaluacion.getEvaluationNumber()
            );
            respuesta.put("motivo", evaluacion.getBusinessReason());
            respuesta.put(
                    "reglasIncumplidas",
                    evaluacion.getFailedRuleCodes()
            );

            List<Map<String, Object>> detalleReglas =
                    reglas.findByCaseEvaluationIdOrderByRuleCodeAsc(
                            evaluacion.getId()
                    )
                    .stream()
                    .map(this::crearDetalleRegla)
                    .toList();

            respuesta.put("reglas", detalleReglas);
        }

        return respuesta;
    }

    /**
     * Devuelve el historial completo de estados en orden cronológico.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerTracking(String identificador) {

        ClaimCase caso = buscarCaso(identificador);

        List<Map<String, Object>> eventos =
                seguimiento.findByClaimCaseIdOrderByEventAtAscIdAsc(
                        caso.getId()
                )
                .stream()
                .map(this::crearDetalleTracking)
                .toList();

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("idCaso", caso.getCaseCode());
        respuesta.put("estadoActual", caso.getCurrentState());
        respuesta.put("cantidadEventos", eventos.size());
        respuesta.put("historial", eventos);

        return respuesta;
    }

    /** Recupera la evaluación vigente, sin alterar el historial anterior. */
    private CaseEvaluation obtenerEvaluacionActual(ClaimCase caso) {

        if (caso.getCurrentEvaluationId() != null) {
            Optional<CaseEvaluation> porId =
                    evaluaciones.findById(caso.getCurrentEvaluationId());

            if (porId.isPresent() && porId.get().isCurrent()) {
                return porId.get();
            }
        }

        return evaluaciones
                .findByClaimCaseIdAndCurrentTrue(caso.getId())
                .orElse(null);
    }

    /** Busca por código visible o por ID numérico de la base de datos. */
    private ClaimCase buscarCaso(String identificador) {

        if (identificador == null || identificador.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debes indicar el código o el ID del caso."
            );
        }

        Optional<ClaimCase> porCodigo =
                casos.findByCaseCode(identificador.trim());

        if (porCodigo.isPresent()) {
            return porCodigo.get();
        }

        try {
            long id = Long.parseLong(identificador.trim());

            return casos.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "No se encontró el caso solicitado."
                    ));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No se encontró el caso solicitado."
            );
        }
    }

    /** Construye una fila segura para el listado y enmascara el documento. */
    private Map<String, Object> crearFilaListado(FilaCaso fila) {

        ClaimCase caso = fila.caso();

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("id", caso.getId());
        resultado.put("idCaso", caso.getCaseCode());
        resultado.put("afiliado", caso.getAffiliateName());
        resultado.put(
                "documentoEnmascarado",
                enmascararDocumento(caso.getDocumentNumber())
        );
        resultado.put("valor", caso.getClaimAmount());
        resultado.put("decision", fila.decision());
        resultado.put("reglasIncumplidas", fila.reglasFallidas());
        resultado.put(
                "motivo",
                fila.evaluacion() == null
                        ? null
                        : fila.evaluacion().getBusinessReason()
        );
        resultado.put("prioridad", fila.prioridad());
        resultado.put("estadoActual", caso.getCurrentState());
        resultado.put("fechaLimiteAns", caso.getAnsDeadlineDate());
        resultado.put("semaforoAns", caso.getAnsSemaphore());
        resultado.put("alertaAns", caso.getAnsAlert());
        resultado.put("analistaAsignado", fila.analista());

        return resultado;
    }

    /** En el listado se muestran únicamente los últimos cuatro caracteres. */
    private String enmascararDocumento(String documento) {

        if (documento == null || documento.isBlank()) {
            return null;
        }

        String limpio = documento.trim();

        if (limpio.length() <= 4) {
            return "******";
        }

        return "******" + limpio.substring(limpio.length() - 4);
    }

    /** Convierte los resultados individuales de las 17 reglas en datos de API. */
    private Map<String, Object> crearDetalleRegla(CaseEvaluationRule regla) {

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("codigo", regla.getRuleCode());
        resultado.put("cumple", regla.isPassed());
        resultado.put("explicacion", regla.getBusinessExplanation());

        return resultado;
    }

    /** Convierte cada evento de seguimiento en un objeto de respuesta seguro. */
    private Map<String, Object> crearDetalleTracking(CaseTracking evento) {

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("estadoAnterior", evento.getPreviousState());
        resultado.put("estadoNuevo", evento.getNewState());
        resultado.put("usuarioId", evento.getActorUserId());
        resultado.put("usuario", evento.getActorLabel());
        resultado.put("fechaHora", evento.getEventAt());
        resultado.put("comentario", evento.getCommentText());

        return resultado;
    }

    private String obtenerNombreAnalista(ClaimCase caso) {

        if (caso.getAssignedAnalystId() == null) {
            return null;
        }

        return usuarios.findById(caso.getAssignedAnalystId())
                .map(Usuario::getUsername)
                .orElse(null);
    }

    private boolean coincide(String filtro, String valorActual) {

        return filtro == null
                || filtro.isBlank()
                || (valorActual != null
                    && valorActual.equalsIgnoreCase(filtro.trim()));
    }

    private int ordenPrioridad(String prioridad) {

        if (prioridad == null || prioridad.isBlank()) {
            return 3;
        }

        return switch (prioridad.trim().toUpperCase(Locale.ROOT)) {
            case "ALTA" -> 0;
            case "MEDIA" -> 1;
            case "BAJA" -> 2;
            default -> 3;
        };
    }

    private record FilaCaso(
            ClaimCase caso,
            CaseEvaluation evaluacion,
            String decision,
            String prioridad,
            List<String> reglasFallidas,
            String analista) {
    }
}
